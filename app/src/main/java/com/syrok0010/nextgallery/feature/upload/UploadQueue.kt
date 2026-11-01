package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
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
    val existing: Int = 0,
    val cancelled: Int = 0,
    val conflicts: Int = 0,
) {
    operator fun plus(other: UploadResult) =
        UploadResult(
            uploaded + other.uploaded,
            files + other.files,
            failures + other.failures,
            details + other.details,
            existing + other.existing,
            cancelled + other.cancelled,
            conflicts + other.conflicts,
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
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val commonFollowUps: () -> List<UploadFollowUp> = { emptyList() },
) {
    private val mutableWaiting = MutableStateFlow<List<UploadBatch>>(emptyList())
    val waiting = mutableWaiting.asStateFlow()
    private val mutableBatches = MutableStateFlow<List<UploadBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()

    fun cancelAll() {
        batches.value.forEach(UploadBatch::cancel)
        finishCancelledWaiting()
    }

    fun cancel(batch: UploadBatch) {
        batch.cancel()
        finishCancelledWaiting()
    }

    fun cancel(item: UploadItem) {
        item.cancel()
        finishCancelledWaiting()
    }

    /** History is only removed explicitly, never when another pass starts or a pill times out. */
    fun clearFinished() {
        mutableBatches.update { batches ->
            batches.filter { batch ->
                batch.items.any {
                    !it.state.value.phase.terminal
                }
            }
        }
    }

    private val started = ConcurrentLinkedQueue<UploadBatch>()
    private val mutableActivity = MutableStateFlow<UploadActivity?>(null)
    val activity = mutableActivity.asStateFlow()
    private val mutableResult = MutableStateFlow<UploadResult?>(null)
    val result = mutableResult.asStateFlow()
    private var sessionGeneration = 0L

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && current != owner)) {
                    synchronized(this@UploadQueue) {
                        sessionGeneration++
                        batches.value.forEach(UploadBatch::cancel)
                        mutableBatches.value = emptyList()
                        mutableWaiting.value = emptyList()
                        started.clear()
                        mutableActivity.value = null
                        mutableResult.value = null
                    }
                }
                owner = current
            }
        }
    }

    /**
     * Queues the phone copies of [mediaIds] as one batch with [followUps] and returns how many
     * files it has. The copies are fixed now, so a later library refresh does not change the batch.
     * Objects that already have a server copy or left the library are skipped.
     */
    fun submit(mediaIds: Collection<MediaId>, followUps: List<UploadFollowUp> = emptyList()): Int {
        val library = library.state.value
        val files = mediaIds.distinct().mapNotNull { id -> library[id]?.let(::uploadSelection) }
        if (files.isNotEmpty()) {
            val batch = UploadBatch(files, commonFollowUps() + followUps)
            mutableBatches.update { it + batch }
            mutableWaiting.update { it + batch }
            mutableActivity.update { current ->
                UploadActivity(
                    pending = current?.pending.orEmpty() + files.map { it.contentUri },
                    total = (current?.total ?: 0) + files.size,
                    processed = current?.processed ?: 0,
                    current = current?.current,
                    percent = current?.percent,
                )
            }
        }
        return files.size
    }

    /** Hands all waiting batches to the service and returns them; one service start takes them all. */
    @Synchronized fun release(): List<UploadBatch> {
        val batches = mutableWaiting.getAndUpdate { emptyList() }
        started.addAll(batches)
        return batches
    }

    /**
     * Takes back [batches] the service has not taken, after the system refused to start it. They
     * wait again ahead of batches submitted since.
     */
    fun restore(batches: List<UploadBatch>) {
        synchronized(this) {
            val back = batches.filter(started::remove)
            if (back.isNotEmpty()) mutableWaiting.update { back + it }
        }
        finishCancelledWaiting()
    }

    /**
     * Enters foreground before any batch is taken. A refusal ends only released work with a
     * visible result instead of leaving an active queue or immediately retrying the same start.
     */
    fun enterForeground(failureReason: String, startForeground: () -> Unit): Boolean {
        try {
            startForeground()
        } catch (_: RuntimeException) {
            // Includes dataSync quota, service type and permission failures from Android.
            val generation = synchronized(this) { sessionGeneration }
            val refused = mutableListOf<UploadBatch>()
            while (true) {
                val batch = next() ?: break
                refused += batch
                batch.items.forEach { it.complete(UploadPhase.Failed, failureReason) }
            }
            if (refused.isNotEmpty()) finishWithoutService(refused, generation)
            return false
        }
        return true
    }

    /** Called by the service until it returns null. */
    @Synchronized fun next(): UploadBatch? = started.poll()

    /** Claims waiting batches only; released work remains part of the service's pass. */
    private fun finishCancelledWaiting() {
        val (cancelled, generation) = synchronized(this) {
            fun UploadBatch.cancelled() = items.all { it.state.value.phase == UploadPhase.Cancelled }
            val waiting = mutableWaiting.value
            val cancelledWaiting = waiting.filter { it.cancelled() }
            if (cancelledWaiting.isNotEmpty()) mutableWaiting.value = waiting - cancelledWaiting.toSet()
            cancelledWaiting to sessionGeneration
        }
        if (cancelled.isNotEmpty()) finishWithoutService(cancelled, generation)
    }

    /** Completes untaken work in process scope, without relying on another service start. */
    private fun finishWithoutService(batches: List<UploadBatch>, generation: Long) {
        scope.launch {
            if (synchronized(this@UploadQueue) { sessionGeneration != generation }) return@launch
            val details = batches.flatMap { finishUploadBatch(it) }
            synchronized(this@UploadQueue) {
                if (sessionGeneration != generation) return@synchronized
                val states = batches.flatMap { batch -> batch.items.map { it.state.value } }
                val cancelled = states.count { it.phase == UploadPhase.Cancelled }
                batches.forEach(::batchFinished)
                passFinished(
                    UploadResult(
                        uploaded = 0,
                        files = states.size,
                        failures = states.mapNotNull { it.failure }.distinct(),
                        cancelled = cancelled,
                        details = buildList {
                            if (cancelled > 0) add(uiText(R.string.upload_cancelled_count, cancelled))
                            addAll(details)
                        },
                    ),
                )
            }
        }
    }

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
                    current = current.current?.takeUnless { it in contentUris },
                    percent = current.percent.takeUnless { current.current in contentUris },
                )
        }
    }

    /**
     * Called by the service when it has run out of batches. The upload stays active until here,
     * through the follow-ups of the last batch, and the result comes before it ends: the app never
     * sees a moment with neither.
     */
    fun passFinished(result: UploadResult) {
        mutableResult.update { it?.plus(result) ?: result }
        mutableActivity.update { current -> current?.takeIf { it.pending.isNotEmpty() } }
    }

    /** Called once the app has shown the result. */
    fun consumeResult() {
        mutableResult.value = null
    }
}
