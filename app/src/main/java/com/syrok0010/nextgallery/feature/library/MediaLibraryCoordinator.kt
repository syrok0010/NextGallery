package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineCommands
import com.syrok0010.nextgallery.feature.timeline.TimelineLoadRange
import com.syrok0010.nextgallery.feature.timeline.TimelineProjectionStore
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflow
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotProjection
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Owns a session's indexers and publishes their reconciled canonical media identities. */
internal class MediaLibraryCoordinator(
    private val index: MediaLibraryIndex,
    private val timeline: TimelineProjectionStore,
    private val sessions: SessionStore,
    private val remoteSource: RemoteTimelineSource,
    private val localUpdates: (Flow<Unit>) -> Flow<LocalMediaIndexState>,
    private val permissions: StateFlow<LocalMediaPermissionMode?>,
) : MediaLibraryCommands, TimelineCommands {
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
                if (session is SessionUiState.SignedIn) coroutineScope {
                    val remote = TimelineWorkflow(session.credentials, remoteSource, this)
                    val local = LocalMediaIndexer(localUpdates, this)
                    val libraryProjection = MediaLibraryProjection()
                    remoteIndexer = remote
                    localIndexer = local
                    try {
                        launch { permissions.collect { it?.let(local::updateAccess) } }
                        combine(remote.state, local.items) { remoteState, localItems ->
                            remoteState to localItems
                        }.collect { (remoteState, localItems) ->
                            val library = libraryProjection.replaceSources(
                                local = localItems,
                                remote = remoteState.snapshot?.items.orEmpty(),
                            )
                            index.publish(library.items)
                            timeline.publish(
                                remoteState,
                                TimelineSnapshotProjection.project(remoteState.snapshot, library),
                            )
                        }
                    } finally {
                        remoteIndexer = null
                        localIndexer = null
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

    override fun refresh() {
        remoteIndexer?.refresh()
        localIndexer?.refresh()
    }

    override fun requestRange(range: TimelineLoadRange) {
        remoteIndexer?.requestRange(range)
    }

    private fun clearPublications() {
        index.clear()
        timeline.clear()
    }
}
