package com.syrok0010.nextgallery.feature.timeline.local

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
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val auid: String?,
    val buid: String?,
) {
    fun toMediaItem(): MediaItem {
        return MediaItem(
            mediaId = mediaId,
            dayId = Math.floorDiv(takenAtEpochSeconds, SECONDS_PER_DAY).toInt(),
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
            ),
        )
    }

    private companion object {
        const val SECONDS_PER_DAY = 86_400L
    }
}
