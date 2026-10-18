package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Upload batches between the caller and the upload service. A batch [waiting] here is started by
 * [QueuedUploads] once a screen is visible: a foreground service may only start from a visible app,
 * and the notification permission dialog needs one too. Batches live in memory only, like the
 * upload itself, and never cross accounts. Every batch also gets the follow-ups from [everyBatch]:
 * work any upload needs, whoever asked for it.
 */
internal class UploadQueue(
    sessions: SessionStore,
    scope: CoroutineScope,
    private val everyBatch: () -> List<UploadFollowUp> = { emptyList() },
) {
    private val mutableWaiting = MutableStateFlow<List<UploadBatch>>(emptyList())
    val waiting = mutableWaiting.asStateFlow()
    private val started = ConcurrentLinkedQueue<UploadBatch>()

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && current != owner)) {
                    mutableWaiting.value = emptyList()
                    started.clear()
                }
                owner = current
            }
        }
    }

    fun submit(batch: UploadBatch) {
        if (batch.files.isEmpty()) return
        val complete = UploadBatch(batch.files, everyBatch() + batch.followUps)
        mutableWaiting.update { it + complete }
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
}
