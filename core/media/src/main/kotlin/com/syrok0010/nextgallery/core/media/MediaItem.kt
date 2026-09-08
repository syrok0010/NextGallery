package com.syrok0010.nextgallery.core.media

import java.time.LocalDate

data class MediaItem(
    val mediaId: MediaId,
    val dayId: Int,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val etag: String?,
    val livePhotoId: String?,
    val auid: String?,
    val buid: String?,
    val sharedBy: String?,
    /** Canonical calendar coordinate used by Memories timeline, not necessarily a UTC instant. */
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val assetRef: MediaAssetRef,
) {
    val day: LocalDate get() = LocalDate.ofEpochDay(dayId.toLong())
    val remoteFileId: Long? get() = when (val asset = assetRef) {
        is MediaAssetRef.MemoriesFile -> asset.photoFileId
        is MediaAssetRef.LocalFirst -> asset.remote.photoFileId
        is MediaAssetRef.LocalContent -> null
    }
}

sealed interface MediaAssetRef {
    data class MemoriesFile(
        val photoFileId: Long,
    ) : MediaAssetRef

    data class LocalContent(
        val contentUri: String,
        val modifiedAtEpochSeconds: Long?,
    ) : MediaAssetRef

    data class LocalFirst(
        val local: LocalContent,
        val remote: MemoriesFile,
    ) : MediaAssetRef
}

val MediaItem.hasRemoteCopy: Boolean
    get() = assetRef is MediaAssetRef.MemoriesFile || assetRef is MediaAssetRef.LocalFirst

val MediaItem.hasLocalCopy: Boolean
    get() = assetRef is MediaAssetRef.LocalContent || assetRef is MediaAssetRef.LocalFirst

/** Validated source projection. Unified LocalFirst objects cannot be fed back as input. */
class LocalMediaProjection(items: List<MediaItem>) {
    val items: List<MediaItem> = java.util.Collections.unmodifiableList(items.toList())
    init {
        require(this.items.all { it.assetRef is MediaAssetRef.LocalContent && it.takenAtEpochSeconds != null }) {
            "Local projection requires local copies with canonical time"
        }
    }
}

class RemoteMediaProjection(items: List<MediaItem>) {
    val items: List<MediaItem> = java.util.Collections.unmodifiableList(items.toList())
    init { require(this.items.all { it.assetRef is MediaAssetRef.MemoriesFile }) { "Remote projection requires remote copies" } }
}

val MediaItem.localCopy: MediaAssetRef.LocalContent?
    get() = when (val asset = assetRef) {
        is MediaAssetRef.LocalContent -> asset
        is MediaAssetRef.LocalFirst -> asset.local
        is MediaAssetRef.MemoriesFile -> null
    }

val MediaItem.remoteCopy: MediaAssetRef.MemoriesFile?
    get() = when (val asset = assetRef) {
        is MediaAssetRef.MemoriesFile -> asset
        is MediaAssetRef.LocalFirst -> asset.remote
        is MediaAssetRef.LocalContent -> null
    }
