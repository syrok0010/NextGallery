package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.CanonicalMediaLibrary
import com.syrok0010.nextgallery.feature.library.LocalLibraryStatus
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
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
    private val credentials: AccountCredentials,
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
            coroutineScope {
                val remote = TimelineWorkflow(credentials, remoteSource, this)
                remoteIndexer = remote
                try {
                    var hasProjected = false
                    var lastLocalItems: List<MediaItem>? = null
                    var lastRemoteSnapshot: TimelineSnapshot? = null
                    var lastLibrary = CanonicalMediaLibrary()
                    combine(
                        remote.state,
                        localMedia.updates(
                            reconcileRequests = localRefresh,
                            access = permissions,
                        ),
                    ) { remoteState, localState ->
                        remoteState to localState
                    }.collect { (remoteState, localState) ->
                        val hasAccess = permissions.value == LocalMediaPermissionMode.Full
                        index.publishLocalStatus(
                            if (!hasAccess) {
                                LocalLibraryStatus()
                            } else {
                                LocalLibraryStatus(
                                    loading = localState.progress != null ||
                                        (!localState.failure && localState.processed == null),
                                    progress = localState.progress,
                                    failed = localState.failure,
                                )
                            },
                        )
                        val localItems = localState.items
                        val sameInputs = hasProjected &&
                            localItems === lastLocalItems &&
                            remoteState.snapshot === lastRemoteSnapshot
                        val library = if (sameInputs) {
                            lastLibrary
                        } else {
                            projectMediaLibrary(
                                localItems = localItems,
                                remoteItems = remoteState.snapshot?.items.orEmpty(),
                            ).also { projected ->
                                hasProjected = true
                                lastLocalItems = localItems
                                lastRemoteSnapshot = remoteState.snapshot
                                lastLibrary = projected
                                index.publish(projected.items)
                            }
                        }
                        publish(LibraryPublication(remoteState, library))
                    }
                } finally {
                    remoteIndexer = null
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

    fun refreshLocal() {
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
