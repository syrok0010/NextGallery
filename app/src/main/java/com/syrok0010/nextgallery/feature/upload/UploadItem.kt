package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.ui.UiText
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
    Cancelled,
    ;

    val terminal: Boolean get() = this != Waiting && this != Uploading
}

internal data class UploadItemState(
    val phase: UploadPhase = UploadPhase.Waiting,
    val percent: Int? = null,
    val failure: String? = null,
)

/** Serializes cancellation with response acceptance, including registration of the next DAV call. */
internal class UploadItem(val selection: UploadSelection) {
    private val mutableState = MutableStateFlow(UploadItemState())
    val state = mutableState.asStateFlow()
    private var call: Call? = null

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

/** Keeps per-file outcomes even when a later file fails. Cancelled files never reach follow-ups. */
internal suspend fun runUploadFiles(
    batch: UploadBatch,
    transfer: suspend (UploadItem) -> UploadedFile?,
    accepted: suspend (UploadedFile) -> Unit,
    failureReason: (Exception) -> String,
    processed: (UploadSelection) -> Unit,
) {
    var failure: String? = null
    for (item in batch.items) {
        try {
            if (!item.state.value.phase.terminal) {
                if (failure != null) {
                    item.complete(UploadPhase.Failed, failure)
                } else {
                    try {
                        val uploaded = transfer(item)
                        val phase = when {
                            uploaded == null -> UploadPhase.NameConflict
                            uploaded.created -> UploadPhase.Uploaded
                            else -> UploadPhase.Existing
                        }
                        if (item.complete(phase) && uploaded != null) accepted(uploaded)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A cancelled socket is not a batch failure; later files can still run.
                        val reason = failureReason(error)
                        if (item.complete(UploadPhase.Failed, reason)) failure = reason
                    }
                }
            }
        } finally {
            processed(item.selection)
        }
    }
}

/** Always resolves batch follow-ups, including a batch whose every file was cancelled. */
internal suspend fun finishUploadBatch(batch: UploadBatch): List<UiText> {
    val missed = batch.items
        .filter {
            it.state.value.phase !in setOf(UploadPhase.Uploaded, UploadPhase.Existing)
        }.map { it.selection }
    return batch.followUps.flatMap { followUp ->
        try {
            followUp.finished(missed)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
}
