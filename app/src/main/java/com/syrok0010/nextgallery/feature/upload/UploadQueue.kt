package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
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

/** The complete in-memory queue snapshot; derived flows keep the service interface small. */
internal data class UploadQueueState(
    val waiting: List<UploadBatch> = emptyList(),
    val released: List<UploadBatch> = emptyList(),
    val activity: UploadActivity? = null,
    val result: UploadResult? = null,
)

/**
 * Upload batches between the caller and the upload service. A batch [waiting] here is started by
 * [QueuedUploads] once a screen is visible: a foreground service may only start from a visible app,
 * and the notification permission dialog needs one too. Batches live in memory only, like the
 * upload itself, and never cross accounts. Every batch also gets the follow-ups from
 * [commonFollowUps]: work any upload needs, whoever asked for it.
 */
internal class UploadQueue(
    sessions: SessionStore,
    private val scope: CoroutineScope,
    private val commonFollowUps: () -> List<UploadFollowUp> = { emptyList() },
) : OperationStatusSource {
    private val mutableState = MutableStateFlow(UploadQueueState())
    private val state = mutableState.asStateFlow()
    val waiting = state.map { it.waiting }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    val activity = state.map { it.activity }.stateIn(scope, SharingStarted.Eagerly, null)
    val result = state.map { it.result }.stateIn(scope, SharingStarted.Eagerly, null)
    override val operations = state
        .map { uploadOperationSnapshots(it.activity, it.result) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && current != owner)) {
                    mutableState.value = UploadQueueState()
                }
                owner = current
            }
        }
    }

    fun submit(batch: UploadBatch) {
        if (batch.files.isEmpty()) return
        val complete = UploadBatch(batch.files, commonFollowUps() + batch.followUps)
        mutableState.update { state ->
            state.copy(
                waiting = state.waiting + complete,
                activity = state.activity.let { current ->
                    UploadActivity(
                        pending = current?.pending.orEmpty() + batch.files.map { it.contentUri },
                        total = (current?.total ?: 0) + batch.files.size,
                        processed = current?.processed ?: 0,
                        current = current?.current,
                        percent = current?.percent,
                    )
                },
            )
        }
    }

    /** Hands all waiting batches to the service and returns them; one service start takes them all. */
    fun release(): List<UploadBatch> {
        val previous = mutableState.getAndUpdate { state ->
            state.copy(waiting = emptyList(), released = state.released + state.waiting)
        }
        return previous.waiting
    }

    /**
     * Takes back [batches] the service has not taken, after the system refused to start it. They
     * wait again ahead of batches submitted since.
     */
    fun restore(batches: List<UploadBatch>) {
        mutableState.update { state ->
            val released = state.released.toMutableList()
            val back = batches.filter(released::remove)
            if (back.isEmpty()) {
                state
            } else {
                state.copy(waiting = back + state.waiting, released = released)
            }
        }
    }

    /** Called by the service until it returns null. */
    fun next(): UploadBatch? {
        val previous = mutableState.getAndUpdate { state ->
            state.copy(released = state.released.drop(1))
        }
        return previous.released.firstOrNull()
    }

    /** Files in batches released to the service but not taken yet, for the overall progress. */
    fun releasedFiles(): Int = state.value.released.sumOf { it.files.size }

    /** Called by the service while [contentUri] is sent; null [percent] means unknown length. */
    fun uploading(contentUri: String, percent: Int?) {
        mutableState.update { state ->
            state.copy(activity = state.activity?.copy(current = contentUri, percent = percent))
        }
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
        mutableState.update { state ->
            val current = state.activity ?: return@update state
            val done = contentUris.filter { it in current.pending }.toSet()
            state.copy(
                activity = current.copy(
                    pending = current.pending - done,
                    processed = current.processed + done.size,
                    current = null,
                    percent = null,
                ),
            )
        }
    }

    /**
     * Called by the service when it has run out of batches. The upload stays active until here,
     * through the follow-ups of the last batch, and the result comes before it ends: the app never
     * sees a moment with neither.
     */
    fun passFinished(result: UploadResult) {
        mutableState.update { state ->
            state.copy(
                result = state.result?.plus(result) ?: result,
                activity = state.activity?.takeIf { it.pending.isNotEmpty() },
            )
        }
    }

    /** Called once the app has shown the result. */
    fun consumeResult() {
        mutableState.update { it.copy(result = null) }
    }

    override fun consumeSucceeded() {
        if (result.value?.let {
                it.failures.isEmpty() && it.uploaded >= it.files
            } == true
        ) {
            consumeResult()
        }
    }

    override fun consumeAll() {
        consumeResult()
    }
}

private fun uploadOperationSnapshots(
    activity: UploadActivity?,
    result: UploadResult?,
): List<OperationStatusSnapshot> =
    buildList {
        if (activity != null) {
            val progress = if (activity.total == 0) {
                null
            } else {
                (activity.processed + (activity.percent ?: 0) / 100f) / activity.total
            }
            val media = buildList {
                activity.current?.let {
                    add(
                        OperationMediaActivity(
                            OperationMediaReference.LocalUri(it),
                            activity.percent?.div(100f),
                        ),
                    )
                }
                activity.pending
                    .asSequence()
                    .filterNot { it == activity.current }
                    .mapTo(this) {
                        OperationMediaActivity(OperationMediaReference.LocalUri(it), 0f)
                    }
            }
            add(
                OperationStatusSnapshot(
                    kind = OperationKind.Upload,
                    title = com.syrok0010.nextgallery.core.ui.uiText(
                        R.string.upload_progress,
                        (activity.processed + 1).coerceAtMost(activity.total),
                        activity.total,
                    ),
                    running = true,
                    progress = progress,
                    media = media,
                ),
            )
        }
        if (result != null) {
            val failed = result.failures.isNotEmpty() || result.uploaded < result.files
            add(
                OperationStatusSnapshot(
                    kind = OperationKind.Upload,
                    title = if (result.failures.isEmpty()) {
                        com.syrok0010.nextgallery.core.ui.uiText(
                            R.string.upload_done,
                            result.uploaded,
                            result.files,
                        )
                    } else {
                        com.syrok0010.nextgallery.core.ui.uiText(
                            R.string.upload_stopped,
                            result.failures.joinToString(", "),
                        )
                    },
                    details = buildList {
                        if (result.failures.isNotEmpty()) {
                            add(
                                com.syrok0010.nextgallery.core.ui.uiText(
                                    R.string.upload_count,
                                    result.uploaded,
                                    result.files,
                                ),
                            )
                        }
                        addAll(result.details)
                    },
                    failed = failed,
                ),
            )
        }
    }
