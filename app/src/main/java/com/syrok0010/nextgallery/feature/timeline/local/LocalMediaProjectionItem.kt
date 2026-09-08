package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate

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
        val dayId = Math.floorDiv(takenAtEpochSeconds, SECONDS_PER_DAY).toInt()
        return MediaItem(
            mediaId = mediaId,
            remoteFileId = null,
            dayId = dayId,
            day = LocalDate.ofEpochDay(dayId.toLong()),
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
