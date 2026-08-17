package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class UnifiedTimelineProjection(
    private val identityRegistry: MediaIdentityRegistry,
) {
    private val mutex = Mutex()
    private var sources = TimelineSources()

    @Volatile
    private var currentSnapshot: TimelineSnapshot? = null

    val snapshot: TimelineSnapshot?
        get() = currentSnapshot

    suspend fun replaceRemoteSnapshot(snapshot: TimelineSnapshot?): UnifiedTimelineProjectionResult =
        updateSources { sources -> sources.copy(remote = snapshot) }

    suspend fun mergeRemoteItems(
        items: List<MediaItem>,
        loadedDayIds: Set<Int>,
    ): UnifiedTimelineProjectionResult = updateSources { sources ->
        sources.copy(
            remote = sources.remote?.let { snapshot ->
                TimelineSnapshotAssembler.mergeLoadedItems(
                    snapshot = snapshot,
                    items = items,
                    loadedDayIds = loadedDayIds,
                )
            },
        )
    }

    suspend fun replaceLocalItems(items: List<MediaItem>): UnifiedTimelineProjectionResult =
        updateSources { sources -> sources.copy(local = items) }

    suspend fun clear() {
        mutex.withLock {
            sources = TimelineSources()
            currentSnapshot = null
        }
    }

    private suspend fun updateSources(
        transform: (TimelineSources) -> TimelineSources,
    ): UnifiedTimelineProjectionResult = mutex.withLock {
        val updatedSources = transform(sources)
        val result = project(
            remoteSnapshot = updatedSources.remote,
            localItems = updatedSources.local,
        )
        sources = updatedSources
        currentSnapshot = result.snapshot
        result
    }

    private suspend fun project(
        remoteSnapshot: TimelineSnapshot?,
        localItems: List<MediaItem>,
    ): UnifiedTimelineProjectionResult {
        val remoteItems = remoteSnapshot?.items.orEmpty()
        val candidates = (localItems + remoteItems).map(MediaItem::identityCandidate)
        val identity = identityRegistry.resolve(candidates)
        val resolvedLocal = localItems.map { item -> item.withResolvedIdentity(identity) }
        val resolvedRemote = remoteItems.map { item -> item.withResolvedIdentity(identity) }
        val localByMediaId = resolvedLocal.associateBy { it.mediaId }
        val remoteBySource = resolvedRemote.associateBy { it.sourceIdentity() }
        val remoteMediaIds = resolvedRemote.mapTo(mutableSetOf()) { it.mediaId }
        val localOnly = resolvedLocal
            .distinctBy { it.mediaId }
            .filterNot { it.mediaId in remoteMediaIds }

        val snapshot = if (remoteSnapshot != null) {
            val resolvedSnapshot = remoteSnapshot.copy(
                slots = remoteSnapshot.slots.map { slot ->
                    val original = slot.mediaItem ?: return@map slot
                    val remote = checkNotNull(remoteBySource[original.sourceIdentity()])
                    val local = localByMediaId[remote.mediaId]
                    slot.copy(
                        mediaItem = if (local == null) {
                            remote
                        } else {
                            remote.copy(
                                assetRef = MediaAssetRef.LocalFirst(
                                    local = local.assetRef as MediaAssetRef.LocalContent,
                                    remote = remote.assetRef as MediaAssetRef.MemoriesFile,
                                ),
                            )
                        },
                    )
                },
            )
            TimelineSnapshotAssembler.addSourceItems(resolvedSnapshot, localOnly).copy(
                loadedDayIds = resolvedSnapshot.loadedDayIds + localOnly.map { it.dayId },
            )
        } else {
            resolvedLocal
                .distinctBy { it.mediaId }
                .takeIf { it.isNotEmpty() }
                ?.let(TimelineSnapshotAssembler::assembleLocal)
        }

        return UnifiedTimelineProjectionResult(
            snapshot = snapshot,
            conflicts = identity.conflicts,
        )
    }
}

private data class TimelineSources(
    val remote: TimelineSnapshot? = null,
    val local: List<MediaItem> = emptyList(),
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
