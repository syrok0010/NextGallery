package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.LocalMediaProjection
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaIdentityConflict
import com.syrok0010.nextgallery.core.media.MediaIdentityResolution
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.RemoteMediaProjection
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.reconcileMediaIdentities
import com.syrok0010.nextgallery.core.media.remoteCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class UnifiedTimelineProjection(
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val mutex = Mutex()
    private var sources = TimelineSources()

    @Volatile
    private var currentSnapshot: TimelineSnapshot? = null

    val snapshot: TimelineSnapshot?
        get() = currentSnapshot

    suspend fun replaceRemoteSnapshot(snapshot: TimelineSnapshot?): UnifiedTimelineProjectionResult {
        snapshot?.let { RemoteMediaProjection(it.items) }
        return updateSources { sources -> sources.copy(remote = snapshot) }
    }

    suspend fun mergeRemoteItems(
        items: RemoteMediaProjection,
        loadedDayIds: Set<Int>,
    ): UnifiedTimelineProjectionResult = updateSources { sources ->
        sources.copy(
            remote = sources.remote?.let { snapshot ->
                TimelineSnapshotAssembler.mergeLoadedItems(
                    snapshot = snapshot,
                    items = items.items,
                    loadedDayIds = loadedDayIds,
                )
            },
        )
    }

    suspend fun replaceLocalItems(items: LocalMediaProjection): UnifiedTimelineProjectionResult =
        updateSources { sources -> sources.copy(local = items.items) }

    suspend fun clear() {
        mutex.withLock {
            sources = TimelineSources()
            currentSnapshot = null
        }
    }

    private suspend fun updateSources(
        transform: (TimelineSources) -> TimelineSources,
    ): UnifiedTimelineProjectionResult = mutex.withLock {
        val update = withContext(computationDispatcher) {
            val updatedSources = transform(sources)
            project(updatedSources.remote, updatedSources.local)
        }
        sources = update.sources
        currentSnapshot = update.result.snapshot
        update.result
    }

    private suspend fun project(
        remoteSnapshot: TimelineSnapshot?,
        localItems: List<MediaItem>,
    ): ProjectedTimelineUpdate {
        val remoteItems = remoteSnapshot?.items.orEmpty()
        val candidates = (localItems + remoteItems).map(MediaItem::identityCandidate)
        val identity = reconcileMediaIdentities(
            candidates = candidates,
            initialSourceMediaIds = emptyMap(),
            initialAliasMediaIds = emptyMap(),
            initialLocalMediaIds = emptySet(),
            mediaIdFactory = {
                error("Timeline sources must resolve persistent MediaIds before projection")
            },
        ).resolution
        val resolvedLocal = localItems.map { item -> item.withResolvedIdentity(identity) }
        val resolvedRemote = remoteItems.map { item -> item.withResolvedIdentity(identity) }
        val localByMediaId = resolvedLocal.associateBy { it.mediaId }
        val remoteBySource = resolvedRemote.associateBy { it.sourceIdentity() }
        val remoteMediaIds = resolvedRemote.mapTo(mutableSetOf()) { it.mediaId }
        val localOnly = resolvedLocal
            .distinctBy { it.mediaId }
            .filterNot { it.mediaId in remoteMediaIds }

        val resolvedRemoteSnapshot = remoteSnapshot?.copy(
            slots = remoteSnapshot.slots.map { slot ->
                val original = slot.mediaItem ?: return@map slot
                slot.copy(mediaItem = checkNotNull(remoteBySource[original.sourceIdentity()]))
            },
        )
        val snapshot = if (resolvedRemoteSnapshot != null) {
            val mergedSnapshot = resolvedRemoteSnapshot.copy(
                slots = resolvedRemoteSnapshot.slots.map { slot ->
                    val original = slot.mediaItem ?: return@map slot
                    val remote = checkNotNull(remoteBySource[original.sourceIdentity()])
                    val local = localByMediaId[remote.mediaId]
                    slot.copy(
                        mediaItem = if (local == null) {
                            remote
                        } else {
                            remote.copy(
                                assetRef = MediaAssetRef.LocalFirst(
                                    local = requireNotNull(local.localCopy),
                                    remote = requireNotNull(remote.remoteCopy),
                                ),
                            )
                        },
                    )
                },
            )
            val remoteDayIds = resolvedRemoteSnapshot.days.mapTo(mutableSetOf()) { it.dayId }
            val localOnlyDayIds = localOnly.mapNotNull { item ->
                item.dayId.takeUnless { it in remoteDayIds }
            }.toSet()
            TimelineSnapshotAssembler.addSourceItems(mergedSnapshot, localOnly).copy(
                loadedDayIds = mergedSnapshot.loadedDayIds + localOnlyDayIds,
            )
        } else {
            resolvedLocal
                .distinctBy { it.mediaId }
                .takeIf { it.isNotEmpty() }
                ?.let(TimelineSnapshotAssembler::assembleLocal)
        }

        return ProjectedTimelineUpdate(
            sources = TimelineSources(
                remote = resolvedRemoteSnapshot,
                local = resolvedLocal,
            ),
            result = UnifiedTimelineProjectionResult(
                snapshot = snapshot,
                conflicts = identity.conflicts,
            ),
        )
    }
}

private data class TimelineSources(
    val remote: TimelineSnapshot? = null,
    val local: List<MediaItem> = emptyList(),
)

private data class ProjectedTimelineUpdate(
    val sources: TimelineSources,
    val result: UnifiedTimelineProjectionResult,
)

data class UnifiedTimelineProjectionResult(
    val snapshot: TimelineSnapshot?,
    val conflicts: List<MediaIdentityConflict>,
)

private fun MediaItem.identityCandidate(): MediaIdentityCandidate = mediaIdentityCandidate(
    source = sourceIdentity(),
    publishedMediaId = mediaId,
    auid = auid,
    buid = buid,
)

private fun MediaItem.withResolvedIdentity(resolution: MediaIdentityResolution): MediaItem =
    copy(mediaId = checkNotNull(resolution.mediaIds[sourceIdentity()]))

private fun MediaItem.sourceIdentity(): MediaSourceIdentity = when (val asset = assetRef) {
    is MediaAssetRef.LocalContent -> MediaSourceIdentity(MediaSourceKind.Local, asset.contentUri)
    is MediaAssetRef.MemoriesFile -> MediaSourceIdentity(MediaSourceKind.Memories, asset.photoFileId.toString())
    is MediaAssetRef.LocalFirst -> error("Unified items cannot be projected as source copies")
}
