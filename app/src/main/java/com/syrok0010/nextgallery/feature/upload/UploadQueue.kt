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
 * upload itself, and never cross accounts.
 */
internal class UploadQueue(sessions: SessionStore, scope: CoroutineScope) {
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
        if (batch.files.isNotEmpty()) mutableWaiting.update { it + batch }
    }

    /** Hands all waiting batches to the service; returns how many service starts they need. */
    fun release(): Int {
        val batches = mutableWaiting.getAndUpdate { emptyList() }
        started.addAll(batches)
        return batches.size
    }

    /** Called by the service once per start. */
    fun next(): UploadBatch? = started.poll()
}
