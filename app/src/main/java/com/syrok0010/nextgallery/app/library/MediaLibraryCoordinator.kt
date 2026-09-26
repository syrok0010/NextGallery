package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.*
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflow
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflowState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Owns a session's indexers and publishes their reconciled canonical media identities. */
internal class MediaLibraryCoordinator(
    private val index: MediaLibraryIndex,
    private val sessions: SessionStore,
    private val remoteSource: RemoteTimelineSource,
    private val localUpdates: (Flow<Unit>) -> Flow<LocalMediaIndexState>,
    private val permissions: StateFlow<LocalMediaPermissionMode?>,
) {
    private val mutablePublication = MutableStateFlow(LibraryPublication())
    val publication = mutablePublication.asStateFlow()
    private val running = Mutex()
    private var remoteIndexer: TimelineWorkflow? = null
    private var localIndexer: LocalMediaIndexer? = null

    suspend fun run() {
        check(running.tryLock()) { "Library indexing already running" }
        try {
            sessions.session.collectLatest { session ->
                remoteIndexer = null
                localIndexer = null
                clearPublications()
                if (session is SessionUiState.SignedIn) {
                    coroutineScope {
                        val remote = TimelineWorkflow(session.credentials, remoteSource, this)
                        val local = LocalMediaIndexer(localUpdates, this)
                        remoteIndexer = remote
                        localIndexer = local
                        try {
                            launch { permissions.collect { it?.let(local::updateAccess) } }
                            combine(remote.state, local.items) { remoteState, localItems ->
                                remoteState to localItems
                            }.collect { (remoteState, localItems) ->
                                val library = projectMediaLibrary(
                                    localItems = localItems,
                                    remoteItems = remoteState.snapshot?.items.orEmpty(),
                                )
                                index.publish(library.items)
                                mutablePublication.value = LibraryPublication(remoteState, library)
                            }
                        } finally {
                            remoteIndexer = null
                            localIndexer = null
                        }
                    }
                }
            }
        } finally {
            remoteIndexer = null
            localIndexer = null
            clearPublications()
            running.unlock()
        }
    }

    fun refresh() {
        remoteIndexer?.refresh()
        localIndexer?.refresh()
    }

    fun requestDays(dayIds: List<Int>, debounced: Boolean = false) {
        remoteIndexer?.requestDays(dayIds, debounced)
    }

    private fun clearPublications() {
        index.clear()
        mutablePublication.value = LibraryPublication()
    }
}

internal data class LibraryPublication(
    val remote: TimelineWorkflowState = TimelineWorkflowState(),
    val library: CanonicalMediaLibrary = CanonicalMediaLibrary(),
)
