package com.syrok0010.nextgallery.feature.upload

import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Response

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
    val retryActions: Boolean = false,
    val linkProblem: String? = null,
    val albumProblem: String? = null,
)

/** Serializes cancellation with response acceptance, including registration of the next DAV call. */
internal class UploadItem(
    val selection: UploadSelection,
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    private val mutableState = MutableStateFlow(UploadItemState())
    val state = mutableState.asStateFlow()
    private var call: Call? = null

    fun restore(state: UploadItemState) {
        mutableState.value = state
    }

    fun stopCall() {
        synchronized(this) { call?.cancel() }
    }

    @Synchronized fun cancel() {
        if (state.value.phase.terminal) return
        mutableState.value = UploadItemState(UploadPhase.Cancelled)
        call?.cancel()
    }

    @Synchronized fun progress(percent: Int?): Boolean {
        if (state.value.phase.terminal) return false
        mutableState.value = UploadItemState(UploadPhase.Uploading, percent)
        return true
    }

    @Synchronized fun complete(phase: UploadPhase, failure: String? = null): Boolean {
        if (state.value.phase.terminal) return false
        mutableState.value = UploadItemState(phase, failure = failure)
        call = null
        return true
    }

    fun execute(next: Call): Response {
        synchronized(this) {
            if (state.value.phase == UploadPhase.Cancelled) {
                next.cancel()
                throw IOException("Upload cancelled")
            }
            call = next
        }
        // Keep the call registered while response bodies are read (not just until headers).
        // The next DAV call replaces it; accepting the outcome releases the last reference.
        return next.execute()
    }
}
