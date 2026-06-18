package com.syrok0010.nextgallery.data.memories

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSlotsTest {
    @Test
    fun `build slots from day counts without loaded items`() {
        val slots = buildTimelineSlots(
            days = listOf(
                TimelineDay(dayId = 19870, count = 2),
                TimelineDay(dayId = 19869, count = 1),
            ),
            itemsByDay = emptyMap(),
        )

        assertEquals(3, slots.size)
        assertEquals(TimelineSlotKey(dayId = 19870, indexInDay = 0), slots[0].key)
        assertEquals(TimelineSlotKey(dayId = 19870, indexInDay = 1), slots[1].key)
        assertEquals(TimelineSlotKey(dayId = 19869, indexInDay = 0), slots[2].key)
        assertNull(slots[0].mediaItem)
    }

    @Test
    fun `merge loaded day items into existing placeholder slots`() {
        val snapshot = TimelineSnapshot(
            config = MemoriesConfig(
                version = "7.5.2",
                timelinePath = "/Photos",
                albumsEnabled = false,
                recognizeEnabled = false,
                faceRecognitionEnabled = false,
                previewGeneratorEnabled = false,
                stackRawFiles = false,
                dedupIdentical = false,
            ),
            days = listOf(TimelineDay(dayId = 19870, count = 2)),
            slots = buildTimelineSlots(
                days = listOf(TimelineDay(dayId = 19870, count = 2)),
                itemsByDay = emptyMap(),
            ),
            loadedDayIds = emptySet(),
            totalDayCount = 1,
            totalMediaCountHint = 2,
        )
        val firstKey = snapshot.slots.first().key

        val updated = snapshot.mergeLoadedItems(
            items = listOf(mediaItem(fileId = 42, dayId = 19870)),
            loadedDayIds = setOf(19870),
        )

        assertEquals(firstKey, updated.slots.first().key)
        assertEquals(42L, updated.slots.first().mediaItem?.fileId)
        assertNull(updated.slots[1].mediaItem)
        assertEquals(setOf(19870), updated.loadedDayIds)
    }

    private fun mediaItem(fileId: Long, dayId: Int): MediaItem {
        return MediaItem(
            fileId = fileId,
            dayId = dayId,
            day = LocalDate.ofEpochDay(dayId.toLong()),
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
            thumbnailUrl = "https://cloud.example.com/thumb/$fileId",
            detailPreviewUrl = "https://cloud.example.com/detail/$fileId",
        )
    }
}
