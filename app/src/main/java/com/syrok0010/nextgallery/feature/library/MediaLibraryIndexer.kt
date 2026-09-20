package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineViewportObservation
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

/** Runs independently of UI. The execution owner supplies lifetime by calling run().
 * Cancellation stops observers, network work and publications together.
 */
internal class MediaLibraryIndexer(
    private val index: MediaLibraryIndex,
    private val timeline: TimelineProjectionStore,
    private val sessions: SessionStore,
    private val source: RemoteTimelineSource,
    private val localUpdates: (Flow<Unit>) -> Flow<LocalMediaIndexState>,
    private val permissions: StateFlow<LocalMediaPermissionMode?>,
) {
    private val running = Mutex()
    private var workflow: MediaIndexingWorkflow? = null

    suspend fun run() {
        check(running.tryLock()) { "Library indexing already running" }
        try {
            sessions.session.collectLatest { session ->
                workflow = null
                index.clear()
                timeline.clear()
                if (session is SessionUiState.SignedIn) {
                    coroutineScope {
                        val active =
                            MediaIndexingWorkflow(session.credentials, source, localUpdates, this)
                        workflow = active
                        try {
                            launch { permissions.collect { it?.let(active::updateLocalAccess) } }
                            active.state.collect { update ->
                                index.publish(update.snapshot?.items.orEmpty())
                                timeline.publish(update)
                            }
                        } finally {
                            workflow = null
                        }
                    }
                }
            }
        } finally {
            workflow = null
            index.clear()
            timeline.clear()
            running.unlock()
        }
    }

    fun refresh() {
        workflow?.refresh()
    }
    fun observeViewport(observation: TimelineViewportObservation) {
        workflow?.observeViewport(observation)
    }
}
