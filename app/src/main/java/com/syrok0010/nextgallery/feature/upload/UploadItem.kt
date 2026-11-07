package com.syrok0010.nextgallery.feature.upload

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UploadPhase {
    Waiting,
    Uploading,
    Uploaded,
    Existing,
    NameConflict,
    Failed,
    NeedsAttention,
    Cancelled,
    ;

    val terminal: Boolean get() = this != Waiting && this != Uploading
}

internal data class UploadItemState(
    val phase: UploadPhase = UploadPhase.Waiting,
    val percent: Int? = null,
    val failure: String? = null,
    val pendingAction: Boolean = false,
    val attention: Boolean = false,
    val actionSuppressed: Boolean = false,
    val albumAdded: String? = null,
    val httpStatus: Int? = null,
)

/** Serializes cancellation with response acceptance and with the start of the file's DAV work. */
internal class UploadItem(
    val selection: UploadSelection,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    private val mutableState = MutableStateFlow(UploadItemState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null

    fun restore(state: UploadItemState) {
        mutableState.value = state
    }

    /** Aborts the running DAV work without deciding the file's outcome. */
    fun stopCall() {
        synchronized(this) { work?.cancel() }
    }

    @Synchronized fun cancel() {
        if (state.value.phase.terminal) return
        mutableState.value = UploadItemState(UploadPhase.Cancelled)
        work?.cancel()
    }

    @Synchronized fun progress(percent: Int?): Boolean {
        if (state.value.phase.terminal) return false
        mutableState.value = UploadItemState(UploadPhase.Uploading, percent)
        return true
    }

    @Synchronized fun complete(phase: UploadPhase, failure: String? = null): Boolean {
        if (state.value.phase.terminal) return false
        mutableState.value = UploadItemState(phase, failure = failure)
        work = null
        return true
    }

    /**
     * Runs the file's DAV work so that [cancel] aborts it: DAV calls cancel their sockets with the
     * coroutine, including a response body still being read. A cancelled file fails with
     * [IOException] like a closed socket; cancellation of the caller still propagates.
     */
    suspend fun <T> run(block: suspend () -> T): T =
        coroutineScope {
            val next = async(start = CoroutineStart.LAZY) { block() }
            synchronized(this@UploadItem) {
                if (state.value.phase == UploadPhase.Cancelled) {
                    next.cancel()
                    throw IOException("Upload cancelled")
                }
                work = next
            }
            try {
                next.await()
            } catch (cancelled: CancellationException) {
                currentCoroutineContext().ensureActive()
                throw IOException("Upload cancelled", cancelled)
            }
        }
}
