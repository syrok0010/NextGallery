package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Upload work shown in the app next to the system notification. [pending] holds content URIs
 * submitted and not processed yet; [current] is on the wire. [total] and [processed] count files
 * since the queue was last empty, so a batch submitted meanwhile extends the same progress.
 */
internal data class UploadActivity(
    val pending: Set<String>,
    val total: Int,
    val processed: Int,
    val current: String? = null,
    val percent: Int? = null,
)

/** Results of service passes the app has not shown yet; [failures] are already localized. */
internal data class UploadResult(
    val uploaded: Int,
    val files: Int,
    val failures: List<String> = emptyList(),
    val details: List<UiText> = emptyList(),
) {
    operator fun plus(other: UploadResult) =
        UploadResult(
            uploaded + other.uploaded,
            files + other.files,
            failures + other.failures,
            details + other.details,
        )
}

/**
 * Upload batches between the caller and the upload service. A batch [waiting] here is started by
 * [QueuedUploads] once a screen is visible: a foreground service may only start from a visible app,
 * and the notification permission dialog needs one too. Batches live in memory only, like the
 * upload itself, and never cross accounts. Every batch also gets the follow-ups from
 * [commonFollowUps]: work any upload needs, whoever asked for it.
 */
internal class UploadQueue(
    sessions: SessionStore,
    scope: CoroutineScope,
    private val commonFollowUps: () -> List<UploadFollowUp> = { emptyList() },
) {
    private val mutableWaiting = MutableStateFlow<List<UploadBatch>>(emptyList())
    val waiting = mutableWaiting.asStateFlow()
    private val started = ConcurrentLinkedQueue<UploadBatch>()
    private val mutableActivity = MutableStateFlow<UploadActivity?>(null)
    val activity = mutableActivity.asStateFlow()
    private val mutableResult = MutableStateFlow<UploadResult?>(null)
    val result = mutableResult.asStateFlow()

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && current != owner)) {
                    mutableWaiting.value = emptyList()
                    started.clear()
                    mutableActivity.value = null
                    mutableResult.value = null
                }
                owner = current
            }
        }
    }

    fun submit(batch: UploadBatch) {
        if (batch.files.isEmpty()) return
        val complete = UploadBatch(batch.files, commonFollowUps() + batch.followUps)
        mutableWaiting.update { it + complete }
        mutableActivity.update { current ->
            UploadActivity(
                pending = current?.pending.orEmpty() + batch.files.map { it.contentUri },
                total = (current?.total ?: 0) + batch.files.size,
                processed = current?.processed ?: 0,
                current = current?.current,
                percent = current?.percent,
            )
        }
    }

    /** Hands all waiting batches to the service and returns them; one service start takes them all. */
    fun release(): List<UploadBatch> {
        val batches = mutableWaiting.getAndUpdate { emptyList() }
        started.addAll(batches)
        return batches
    }

    /**
     * Takes back [batches] the service has not taken, after the system refused to start it. They
     * wait again ahead of batches submitted since.
     */
    fun restore(batches: List<UploadBatch>) {
        val back = batches.filter(started::remove)
        if (back.isNotEmpty()) mutableWaiting.update { back + it }
    }

    /** Called by the service until it returns null. */
    fun next(): UploadBatch? = started.poll()

    /** Files in batches released to the service but not taken yet, for the overall progress. */
    fun releasedFiles(): Int = started.sumOf { it.files.size }

    /** Called by the service while [contentUri] is sent; null [percent] means unknown length. */
    fun uploading(contentUri: String, percent: Int?) {
        mutableActivity.update { it?.copy(current = contentUri, percent = percent) }
    }

    /** Called by the service once [contentUri] is on the server or skipped. */
    fun processed(contentUri: String) {
        processed(listOf(contentUri))
    }

    /** Called by the service after [batch]: files it did not get to after an error are done too. */
    fun batchFinished(batch: UploadBatch) {
        processed(batch.files.map { it.contentUri })
    }

    private fun processed(contentUris: List<String>) {
        mutableActivity.update { current ->
            val done = contentUris.filter { it in current?.pending.orEmpty() }.toSet()
            current
                ?.copy(
                    pending = current.pending - done,
                    processed = current.processed + done.size,
                    current = null,
                    percent = null,
                )?.takeIf { it.pending.isNotEmpty() }
        }
    }

    /** Called by the service when it has run out of batches. */
    fun passFinished(result: UploadResult) {
        mutableResult.update { it?.plus(result) ?: result }
    }

    /** Called once the app has shown the result. */
    fun consumeResult() {
        mutableResult.value = null
    }
}
