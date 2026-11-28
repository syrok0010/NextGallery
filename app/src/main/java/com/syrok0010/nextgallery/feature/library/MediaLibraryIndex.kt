package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.remoteCopy
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Indexing state of the single device-wide MediaStore scan published alongside [MediaLibraryIndex.state]. */
internal data class LocalLibraryStatus(
    val hasAccess: Boolean = false,
    val loading: Boolean = false,
    val initial: Boolean = false,
    val progress: LocalMediaIndexProgress? = null,
    val failed: Boolean = false,
)

/**
 * Session-scoped catalog of known media objects keyed by [MediaId].
 *
 * Combines the reconciled library publication (device index + hydrated timeline days) with
 * remote copies observed in other collections (such as albums) so every feature can resolve
 * any visible [MediaId] and its merged copies from one place.
 */
internal class MediaLibraryIndex {
    private data class Snapshot(
        val published: Map<MediaId, MediaItem> = emptyMap(),
        val observedRemote: Map<MediaId, MediaItem> = emptyMap(),
    ) {
        val items: Map<MediaId, MediaItem> = mergeCatalog(published, observedRemote)
    }

    private val snapshot = MutableStateFlow(Snapshot())
    private val mutableState = MutableStateFlow<Map<MediaId, MediaItem>>(emptyMap())
    private val mutableLocalStatus = MutableStateFlow(LocalLibraryStatus())
    val state = mutableState.asStateFlow()
    val localStatus = mutableLocalStatus.asStateFlow()

    operator fun get(mediaId: MediaId): MediaItem? = state.value[mediaId]

    fun findByRemoteFileId(fileId: Long): MediaItem? =
        state.value.values.firstOrNull { it.remoteFileId == fileId }

    internal fun publish(items: List<MediaItem>) =
        update {
            copy(published = items.associateBy { it.mediaId })
        }

    internal fun publishLocalStatus(status: LocalLibraryStatus) {
        mutableLocalStatus.value = status
    }

    /** Records remote copies loaded outside the timeline and merges them with known local copies. */
    internal fun observeRemote(items: Collection<MediaItem>) {
        val incoming = items.mapNotNull { item ->
            val remote = item.remoteCopy ?: return@mapNotNull null
            item.mediaId to if (item.assetRef == remote) item else item.copy(assetRef = remote)
        }
        if (incoming.isNotEmpty()) update { copy(observedRemote = observedRemote + incoming) }
    }

    /** Drops confirmed deleted cloud copies immediately without waiting for the next timeline refresh. */
    internal fun forgetRemote(fileIds: Set<Long>) {
        if (fileIds.isEmpty()) return
        update {
            copy(
                published = published.values
                    .mapNotNull { item ->
                        if (item.remoteFileId !in fileIds) {
                            item
                        } else {
                            item.localCopy?.let { item.copy(assetRef = it) }
                        }
                    }.associateBy { it.mediaId },
                observedRemote = observedRemote.filterValues { it.remoteFileId !in fileIds },
            )
        }
    }

    internal fun clear() {
        update { Snapshot() }
        mutableLocalStatus.value = LocalLibraryStatus()
    }

    private fun update(transform: Snapshot.() -> Snapshot) {
        mutableState.value = snapshot.updateAndGet(transform).items
    }
}

private fun mergeCatalog(
    published: Map<MediaId, MediaItem>,
    observedRemote: Map<MediaId, MediaItem>,
): Map<MediaId, MediaItem> {
    if (observedRemote.isEmpty()) return published
    val mergedRemote = observedRemote.mapValues { (id, remoteItem) ->
        val existing = published[id]
        when {
            existing == null -> remoteItem

            existing.remoteCopy != null -> existing

            existing.localCopy != null -> remoteItem.copy(
                assetRef = MediaAssetRef.LocalFirst(
                    requireNotNull(existing.localCopy),
                    requireNotNull(remoteItem.remoteCopy),
                ),
            )

            else -> remoteItem
        }
    }
    return published + mergedRemote
}

/** Resolves persistent [MediaId]s for Memories photos and optionally registers them in [index]. */
internal suspend fun ingestRemotePhotos(
    photos: List<MemoriesPhotoDto>,
    identities: MediaIdentityRegistry,
    index: MediaLibraryIndex? = null,
): List<MediaItem> {
    if (photos.isEmpty()) return emptyList()
    val candidates = photos.distinctBy { it.fileid }.map { photo ->
        mediaIdentityCandidate(
            source = MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
            auid = photo.auid,
            buid = photo.buid,
        )
    }
    val resolution = identities.resolve(candidates)
    val items = photos.map { photo ->
        photo.toMemoriesMediaItem(
            resolution.mediaIds.getValue(
                MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
            ),
        )
    }
    index?.observeRemote(items)
    return items
}

internal fun MemoriesPhotoDto.toMemoriesMediaItem(mediaId: MediaId): MediaItem {
    val isVideoValue = isVideo.asFlexibleBoolean() || mimetype?.startsWith("video/") == true
    return MediaItem(
        mediaId = mediaId,
        dayId = dayid,
        displayName = basename ?: "file-$fileid",
        mimeType = mimetype,
        width = w,
        height = h,
        etag = etag,
        indexedAtEpochSeconds = indexedAt,
        livePhotoId = liveid,
        auid = auid,
        buid = buid,
        sharedBy = sharedBy,
        takenAtEpochSeconds = epoch,
        isVideo = isVideoValue,
        videoDurationSeconds = videoDuration,
        isFavorite = isFavorite.asFlexibleBoolean(),
        isHidden = isHidden.asFlexibleBoolean(),
        assetRef = MediaAssetRef.MemoriesFile(photoFileId = fileid),
    )
}

private fun JsonElement?.asFlexibleBoolean(): Boolean {
    val primitive = this?.jsonPrimitive ?: return false
    return primitive.booleanOrNull
        ?: primitive.intOrNull?.let { it != 0 }
        ?: primitive.content.equals("true", ignoreCase = true) ||
        primitive.content == "1"
}
