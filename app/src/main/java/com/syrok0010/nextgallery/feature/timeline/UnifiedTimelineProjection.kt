package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.LocalMediaProjection
import com.syrok0010.nextgallery.core.media.RemoteMediaProjection
import com.syrok0010.nextgallery.feature.library.MediaLibraryProjection
import com.syrok0010.nextgallery.feature.library.sourceIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stateful facade used when callers need canonical reconciliation and a timeline in one operation. */
class UnifiedTimelineProjection(computationDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val mutex = Mutex()
    private val library = MediaLibraryProjection(computationDispatcher)
    private var remoteSnapshot: TimelineSnapshot? = null
    private var localItems = emptyList<com.syrok0010.nextgallery.core.media.MediaItem>()

    @Volatile
    private var currentSnapshot: TimelineSnapshot? = null

    val snapshot: TimelineSnapshot?
        get() = currentSnapshot

    suspend fun replaceRemoteSnapshot(
        snapshot: TimelineSnapshot?,
    ): UnifiedTimelineProjectionResult =
        mutex.withLock {
            remoteSnapshot = snapshot
            project()
        }

    suspend fun mergeRemoteItems(
        items: RemoteMediaProjection,
        loadedDayIds: Set<Int>,
    ): UnifiedTimelineProjectionResult =
        mutex.withLock {
            remoteSnapshot = remoteSnapshot?.let { snapshot ->
                TimelineSnapshotAssembler.mergeLoadedItems(snapshot, items.items, loadedDayIds)
            }
            project()
        }

    suspend fun replaceLocalItems(items: LocalMediaProjection): UnifiedTimelineProjectionResult =
        mutex.withLock {
            localItems = items.items
            project()
        }

    suspend fun clear() {
        mutex.withLock {
            remoteSnapshot = null
            localItems = emptyList()
            currentSnapshot = null
        }
    }

    private suspend fun project(): UnifiedTimelineProjectionResult {
        val canonical = library.replaceSources(localItems, remoteSnapshot?.items.orEmpty())
        remoteSnapshot = remoteSnapshot?.copy(
            slots = remoteSnapshot!!.slots.map { slot ->
                val item = slot.mediaItem ?: return@map slot
                slot.copy(
                    mediaItem = item.copy(
                        mediaId = checkNotNull(canonical.mediaIdsBySource[item.sourceIdentity()]),
                    ),
                )
            },
        )
        val snapshot = TimelineSnapshotProjection.project(remoteSnapshot, canonical)
        currentSnapshot = snapshot
        return UnifiedTimelineProjectionResult(snapshot, canonical.conflicts)
    }
}

data class UnifiedTimelineProjectionResult(
    val snapshot: TimelineSnapshot?,
    val conflicts: List<com.syrok0010.nextgallery.core.media.MediaIdentityConflict>,
)
