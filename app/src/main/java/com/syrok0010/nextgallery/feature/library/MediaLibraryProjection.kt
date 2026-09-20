package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaIdentityConflict
import com.syrok0010.nextgallery.core.media.MediaIdentityResolution
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.reconcileMediaIdentities
import com.syrok0010.nextgallery.core.media.remoteCopy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CanonicalMediaLibrary(
    val items: List<MediaItem> = emptyList(),
    val mediaIdsBySource: Map<MediaSourceIdentity, MediaId> = emptyMap(),
    val conflicts: List<MediaIdentityConflict> = emptyList(),
)

/** Reconciles source copies into canonical media objects without knowing any UI collection. */
internal class MediaLibraryProjection(
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var localItems: List<MediaItem> = emptyList()
    private var remoteItems: List<MediaItem> = emptyList()

    suspend fun replaceSources(
        local: List<MediaItem>,
        remote: List<MediaItem>,
    ): CanonicalMediaLibrary = withContext(computationDispatcher) {
        localItems = local
        remoteItems = remote
        project()
    }

    private fun project(): CanonicalMediaLibrary {
        val candidates = (localItems + remoteItems).map(MediaItem::identityCandidate)
        val identity = reconcileMediaIdentities(
            candidates = candidates,
            initialSourceMediaIds = emptyMap(),
            initialAliasMediaIds = emptyMap(),
            initialLocalMediaIds = emptySet(),
            mediaIdFactory = { error("Media sources must resolve persistent MediaIds before projection") },
        ).resolution
        val resolvedLocal = localItems.map { it.withResolvedIdentity(identity) }
        val resolvedRemote = remoteItems.map { it.withResolvedIdentity(identity) }
        val localByMediaId = resolvedLocal.associateBy(MediaItem::mediaId)
        val remoteMediaIds = resolvedRemote.mapTo(mutableSetOf(), MediaItem::mediaId)
        val canonicalRemote = resolvedRemote.map { remote ->
            val local = localByMediaId[remote.mediaId] ?: return@map remote
            remote.copy(
                assetRef = MediaAssetRef.LocalFirst(
                    local = requireNotNull(local.localCopy),
                    remote = requireNotNull(remote.remoteCopy),
                ),
            )
        }
        val localOnly = resolvedLocal.distinctBy(MediaItem::mediaId).filterNot { it.mediaId in remoteMediaIds }
        localItems = resolvedLocal
        remoteItems = resolvedRemote
        return CanonicalMediaLibrary(
            items = canonicalRemote + localOnly,
            mediaIdsBySource = identity.mediaIds,
            conflicts = identity.conflicts,
        )
    }
}

private fun MediaItem.identityCandidate(): MediaIdentityCandidate = mediaIdentityCandidate(
    source = sourceIdentity(),
    publishedMediaId = mediaId,
    auid = auid,
    buid = buid,
)

private fun MediaItem.withResolvedIdentity(resolution: MediaIdentityResolution): MediaItem =
    copy(mediaId = checkNotNull(resolution.mediaIds[sourceIdentity()]))

internal fun MediaItem.sourceIdentity(): MediaSourceIdentity = when (val asset = assetRef) {
    is MediaAssetRef.LocalContent -> MediaSourceIdentity(MediaSourceKind.Local, asset.contentUri)
    is MediaAssetRef.MemoriesFile -> MediaSourceIdentity(MediaSourceKind.Memories, asset.photoFileId.toString())
    is MediaAssetRef.LocalFirst -> error("Canonical media objects are not source copies")
}
