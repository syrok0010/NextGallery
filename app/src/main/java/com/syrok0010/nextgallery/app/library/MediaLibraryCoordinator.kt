package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.CanonicalMediaLibrary
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflow
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflowState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex

/** Owns a session's indexers and publishes their reconciled canonical media identities. */
internal class MediaLibraryCoordinator(
    private val index: MediaLibraryIndex,
    private val sessions: SessionStore,
    private val remoteSource: RemoteTimelineSource,
    private val localMedia: LocalMediaSource,
    private val permissions: StateFlow<LocalMediaPermissionMode?>,
) {
    private val running = Mutex()
    private var remoteIndexer: TimelineWorkflow? = null
    private val localRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    suspend fun run(publish: suspend (LibraryPublication) -> Unit) {
        check(running.tryLock()) { "Library indexing already running" }
        try {
            sessions.session.collectLatest { session ->
                remoteIndexer = null
                clearIndex()
                publish(LibraryPublication())
                if (session is SessionUiState.SignedIn) {
                    coroutineScope {
                        val remote = TimelineWorkflow(session.credentials, remoteSource, this)
                        remoteIndexer = remote
                        try {
                            combine(
                                remote.state,
                                localMedia.updates(
                                    reconcileRequests = localRefresh,
                                    access = permissions,
                                ),
                            ) { remoteState, localState ->
                                remoteState to localState.items
                            }.collect { (remoteState, localItems) ->
                                val library = projectMediaLibrary(
                                    localItems = localItems,
                                    remoteItems = remoteState.snapshot?.items.orEmpty(),
                                )
                                index.publish(library.items)
                                publish(LibraryPublication(remoteState, library))
                            }
                        } finally {
                            remoteIndexer = null
                        }
                    }
                }
            }
        } finally {
            remoteIndexer = null
            clearIndex()
            publish(LibraryPublication())
            running.unlock()
        }
    }

    fun refresh() {
        remoteIndexer?.refresh()
        localRefresh.tryEmit(Unit)
    }

    fun requestDays(dayIds: List<Int>, debounced: Boolean = false) {
        remoteIndexer?.requestDays(dayIds, debounced)
    }

    private fun clearIndex() {
        index.clear()
    }
}

internal data class LibraryPublication(
    val remote: TimelineWorkflowState = TimelineWorkflowState(),
    val library: CanonicalMediaLibrary = CanonicalMediaLibrary(),
)
