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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Session-scoped catalog of known media objects keyed by [MediaId].
 *
 * Combines the reconciled library publication (device index + hydrated timeline days) with
 * remote copies observed in other collections (such as albums) so every feature can resolve
 * any visible [MediaId] and its merged copies from one place.
 */
internal class MediaLibraryIndex {
    private val lock = Any()
    private var published: Map<MediaId, MediaItem> = emptyMap()
    private val observedRemote = LinkedHashMap<MediaId, MediaItem>()
    private val mutableState = MutableStateFlow<Map<MediaId, MediaItem>>(emptyMap())
    val state = mutableState.asStateFlow()

    operator fun get(mediaId: MediaId): MediaItem? = state.value[mediaId]

    fun findByRemoteFileId(fileId: Long): MediaItem? =
        state.value.values.firstOrNull { it.remoteFileId == fileId }

    internal fun publish(items: List<MediaItem>) {
        synchronized(lock) {
            published = items.associateBy { it.mediaId }
            mutableState.value = rebuildLocked()
        }
    }

    /** Records remote copies loaded outside the timeline and merges them with known local copies. */
    internal fun observeRemote(items: Collection<MediaItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            var changed = false
            items.forEach { item ->
                val remote = item.remoteCopy ?: return@forEach
                val remoteOnly = if (item.assetRef == remote) item else item.copy(assetRef = remote)
                if (observedRemote.put(item.mediaId, remoteOnly) != remoteOnly) {
                    changed = true
                }
            }
            if (changed) mutableState.value = rebuildLocked()
        }
    }

    /** Drops confirmed deleted cloud copies immediately without waiting for the next timeline refresh. */
    internal fun forgetRemote(fileIds: Set<Long>) {
        if (fileIds.isEmpty()) return
        synchronized(lock) {
            var changed = observedRemote.entries.removeAll { (_, item) ->
                item.remoteFileId in fileIds
            }
            if (published.values.any { it.remoteFileId in fileIds }) {
                published = buildMap(published.size) {
                    published.forEach { (id, item) ->
                        if (item.remoteFileId !in fileIds) {
                            put(id, item)
                        } else {
                            item.localCopy?.let { local -> put(id, item.copy(assetRef = local)) }
                        }
                    }
                }
                changed = true
            }
            if (changed) mutableState.value = rebuildLocked()
        }
    }

    internal fun clear() {
        synchronized(lock) {
            published = emptyMap()
            observedRemote.clear()
            mutableState.value = emptyMap()
        }
    }

    private fun rebuildLocked(): Map<MediaId, MediaItem> {
        if (observedRemote.isEmpty()) return published
        val merged = LinkedHashMap<MediaId, MediaItem>(published.size + observedRemote.size)
        merged.putAll(published)
        observedRemote.forEach { (id, remoteItem) ->
            val remote = remoteItem.remoteCopy ?: return@forEach
            val existing = merged[id]
            merged[id] = when {
                existing == null -> remoteItem

                existing.remoteCopy != null -> existing

                existing.localCopy != null -> remoteItem.copy(
                    assetRef = MediaAssetRef.LocalFirst(requireNotNull(existing.localCopy), remote),
                )

                else -> remoteItem
            }
        }
        return merged
    }
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
