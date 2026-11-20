package com.syrok0010.nextgallery.feature.library.local

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem

internal data class LocalMediaProjectionItem(
    val mediaId: MediaId,
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val takenAtEpochSeconds: Long,
    val modifiedAtEpochSeconds: Long?,
    val sizeBytes: Long,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val auid: String?,
    val buid: String?,
    val dayId: Int = localMediaDayId(takenAtEpochSeconds),
) {
    fun toMediaItem(): MediaItem =
        MediaItem(
            mediaId = mediaId,
            dayId = dayId,
            displayName = displayName,
            mimeType = mimeType,
            width = width,
            height = height,
            etag = null,
            livePhotoId = null,
            auid = auid,
            buid = buid,
            sharedBy = null,
            takenAtEpochSeconds = takenAtEpochSeconds,
            isVideo = isVideo,
            videoDurationSeconds = videoDurationSeconds,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = contentUri,
                modifiedAtEpochSeconds = modifiedAtEpochSeconds,
                sizeBytes = sizeBytes,
            ),
        )
}
