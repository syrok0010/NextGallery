package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Maintains local metadata without knowing timeline or album projections. */
internal class LocalMediaIndexer(
    private val updates: (Flow<Unit>) -> Flow<LocalMediaIndexState>,
    private val scope: CoroutineScope,
) {
    private val mutableItems = MutableStateFlow<List<MediaItem>>(emptyList())
    val items = mutableItems.asStateFlow()
    private val requests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var permission = LocalMediaPermissionMode.Denied
    private var indexingJob: Job? = null

    fun updateAccess(mode: LocalMediaPermissionMode) {
        val previousMode = permission
        permission = mode
        if (mode != LocalMediaPermissionMode.Full) {
            indexingJob?.cancel()
            indexingJob = null
            mutableItems.value = emptyList()
            return
        }
        if (previousMode == LocalMediaPermissionMode.Full && indexingJob?.isActive == true) {
            refresh()
            return
        }
        indexingJob?.cancel()
        indexingJob = updates(requests)
            .onEach { mutableItems.value = it.items }
            .catch { /* permission changes or a new session can restart collection */ }
            .launchIn(scope)
    }

    fun refresh() {
        if (permission == LocalMediaPermissionMode.Full) requests.tryEmit(Unit)
    }
}
