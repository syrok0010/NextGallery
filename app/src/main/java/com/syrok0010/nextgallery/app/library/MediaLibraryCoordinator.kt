package com.syrok0010.nextgallery.app.library

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.CanonicalMediaLibrary
import com.syrok0010.nextgallery.feature.library.LocalLibraryStatus
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.library.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflow
import com.syrok0010.nextgallery.feature.timeline.TimelineWorkflowState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
                    val remoteSnapshots = remote.state
                        .map { it.snapshot }
                        .distinctUntilChanged { a, b -> a === b }
                    val localItems = localMedia
                        .updates(reconcileRequests = localRefresh, access = permissions)
                        .onEach { index.publishLocalStatus(it.toLibraryStatus(permissions.value)) }
                        .map { it.items }
                        .distinctUntilChanged { a, b -> a === b }
                    val projected = combine(remoteSnapshots, localItems) { snapshot, items ->
                        val library = projectMediaLibrary(items, snapshot?.items.orEmpty())
                        index.publish(library.items)
                        snapshot to library
                    }
                    combine(remote.state, projected) { remoteState, (snapshot, library) ->
                        if (remoteState.snapshot === snapshot) {
                            LibraryPublication(
                                remoteState,
                                library,
                            )
                        } else {
                            null
                        }
                    }.filterNotNull().collect(publish)
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

private fun LocalMediaIndexState.toLibraryStatus(
    permission: LocalMediaPermissionMode?,
): LocalLibraryStatus =
    if (permission != LocalMediaPermissionMode.Full) {
        LocalLibraryStatus()
    } else {
        LocalLibraryStatus(
            loading = progress != null || (!failure && processed == null),
            progress = progress,
            failed = failure,
        )
    }

internal data class LibraryPublication(
    val remote: TimelineWorkflowState = TimelineWorkflowState(),
    val library: CanonicalMediaLibrary = CanonicalMediaLibrary(),
)
