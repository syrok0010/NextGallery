package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.collection.MediaSlot
import com.syrok0010.nextgallery.feature.collection.MediaSlotKey
import com.syrok0010.nextgallery.feature.collection.toMediaGridItems
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineGridItemsTest {
    @Test
    fun `loaded grid tile key follows media id instead of remote file id or slot`() {
        val firstSlot = slot(
            index = 0,
            item = mediaItem(MediaId("stable-media-id"), fileId = 41L),
        )
        val movedSlot = slot(
            index = 7,
            item = mediaItem(MediaId("stable-media-id"), fileId = 99L),
        )

        val firstKey = listOf(firstSlot).toMediaGridItems().last().key
        val movedKey = listOf(movedSlot).toMediaGridItems().last().key

        assertEquals("media:stable-media-id", firstKey)
        assertEquals(firstKey, movedKey)
    }

    private fun slot(index: Int, item: MediaItem) =
        MediaSlot(
            key = MediaSlotKey(dayId = DAY_ID, indexInDay = index),
            dayId = DAY_ID,
            indexInDay = index,
            mediaItem = item,
        )

    private fun mediaItem(mediaId: MediaId, fileId: Long) =
        MediaItem(
            mediaId = mediaId,
            dayId = DAY_ID,
            displayName = "file-$fileId",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = null,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.MemoriesFile(photoFileId = fileId),
        )

    private companion object {
        const val DAY_ID = 20_645
    }
}
