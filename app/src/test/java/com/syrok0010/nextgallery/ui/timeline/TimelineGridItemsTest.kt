package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
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

        val firstKey = listOf(firstSlot).toTimelineGridItems().last().key
        val movedKey = listOf(movedSlot).toTimelineGridItems().last().key

        assertEquals("media:stable-media-id", firstKey)
        assertEquals(firstKey, movedKey)
    }

    private fun slot(index: Int, item: MediaItem) = TimelineSlot(
        key = TimelineSlotKey(dayId = DAY_ID, indexInDay = index),
        dayId = DAY_ID,
        indexInDay = index,
        mediaItem = item,
    )

    private fun mediaItem(mediaId: MediaId, fileId: Long) = MediaItem(
        mediaId = mediaId,
        remoteFileId = fileId,
        dayId = DAY_ID,
        day = LocalDate.ofEpochDay(DAY_ID.toLong()),
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
