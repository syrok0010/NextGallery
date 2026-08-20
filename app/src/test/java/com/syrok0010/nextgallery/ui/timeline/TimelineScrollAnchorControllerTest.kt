package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineScrollAnchorControllerTest {
    @Test
    fun `adding media above viewport restores the same media and pixel offset`() {
        val controller = TimelineScrollAnchorController()
        val anchor = mediaItem("anchor", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000)
        val initialItems = gridItems(anchor)

        val anchorGridIndex = initialItems.indexOfMedia(anchor.mediaId)
        val newer = mediaItem("newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400)
        val restoration = controller.restorationForUpdate(
            previousGridItems = initialItems,
            newGridItems = gridItems(newer, anchor),
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = anchorGridIndex,
                    key = "media:${anchor.mediaId.value}",
                    viewportOffsetPx = -24,
                ),
            ),
            isRestorationAllowed = true,
        )

        requireNotNull(restoration)
        assertEquals(anchor.mediaId, restoration.mediaId)
        assertEquals(3, restoration.gridIndex)
        assertEquals(24, restoration.scrollOffsetPx)
    }

    @Test
    fun `removing media above viewport restores the same media and pixel offset`() {
        val controller = TimelineScrollAnchorController()
        val newer = mediaItem("newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400)
        val anchor = mediaItem("anchor", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000)
        val initialItems = gridItems(newer, anchor)
        val restoration = controller.restorationForUpdate(
            previousGridItems = initialItems,
            newGridItems = gridItems(anchor),
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = initialItems.indexOfMedia(anchor.mediaId),
                    key = "media:${anchor.mediaId.value}",
                    viewportOffsetPx = 18,
                ),
            ),
            isRestorationAllowed = true,
        )

        requireNotNull(restoration)
        assertEquals(anchor.mediaId, restoration.mediaId)
        assertEquals(1, restoration.gridIndex)
        assertEquals(-18, restoration.scrollOffsetPx)
    }

    @Test
    fun `merging source copies follows the stable media id`() {
        val controller = TimelineScrollAnchorController()
        val local = mediaItem("stable", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000).copy(
            remoteFileId = null,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://media/external/images/media/42",
                modifiedAtEpochSeconds = 1_728_000_100,
            ),
        )
        val initialItems = gridItems(local)
        val merged = local.copy(
            remoteFileId = 42,
            assetRef = MediaAssetRef.LocalFirst(
                local = local.assetRef as MediaAssetRef.LocalContent,
                remote = MediaAssetRef.MemoriesFile(42),
            ),
        )

        val restoration = controller.restorationForUpdate(
            previousGridItems = initialItems,
            newGridItems = gridItems(merged),
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = initialItems.indexOfMedia(local.mediaId),
                    key = "media:${local.mediaId.value}",
                    viewportOffsetPx = -12,
                ),
            ),
            isRestorationAllowed = true,
        )

        requireNotNull(restoration)
        assertEquals(local.mediaId, restoration.mediaId)
        assertEquals(12, restoration.scrollOffsetPx)
    }

    @Test
    fun `removed anchor falls back to media nearest by canonical time`() {
        val controller = TimelineScrollAnchorController()
        val anchor = mediaItem("removed", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000)
        val initialItems = gridItems(anchor)
        val farther = mediaItem("farther", dayId = 20_000, takenAtEpochSeconds = 1_727_999_000)
        val nearest = mediaItem("nearest", dayId = 20_000, takenAtEpochSeconds = 1_728_000_010)

        val updatedItems = gridItems(nearest, farther)
        val restoration = controller.restorationForUpdate(
            previousGridItems = initialItems,
            newGridItems = updatedItems,
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = initialItems.indexOfMedia(anchor.mediaId),
                    key = "media:${anchor.mediaId.value}",
                    viewportOffsetPx = -8,
                ),
            ),
            isRestorationAllowed = true,
        )

        requireNotNull(restoration)
        assertEquals(nearest.mediaId, restoration.mediaId)
        assertEquals(updatedItems.indexOfMedia(nearest.mediaId), restoration.gridIndex)
        assertEquals(8, restoration.scrollOffsetPx)
    }

    @Test
    fun `timeline rebuild does not override active scrollbar navigation`() {
        val controller = TimelineScrollAnchorController()
        val anchor = mediaItem("anchor", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000)
        val initialItems = gridItems(anchor)
        val restoration = controller.restorationForUpdate(
            previousGridItems = initialItems,
            newGridItems = gridItems(
                mediaItem("newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400),
                anchor,
            ),
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = initialItems.indexOfMedia(anchor.mediaId),
                    key = "media:${anchor.mediaId.value}",
                    viewportOffsetPx = 0,
                ),
            ),
            isRestorationAllowed = false,
        )

        assertNull(restoration)
    }

    @Test
    fun `measured item key identifies anchor when a rapid update makes its index stale`() {
        val controller = TimelineScrollAnchorController()
        val newer = mediaItem("newer", dayId = 20_001, takenAtEpochSeconds = 1_728_086_400)
        val anchor = mediaItem("anchor", dayId = 20_000, takenAtEpochSeconds = 1_728_000_000)
        val previousItems = gridItems(newer, anchor)

        val restoration = controller.restorationForUpdate(
            previousGridItems = previousItems,
            newGridItems = gridItems(anchor),
            visibleItems = listOf(
                TimelineVisibleGridItem(
                    gridIndex = 1,
                    key = "media:${anchor.mediaId.value}",
                    viewportOffsetPx = -6,
                ),
            ),
            isRestorationAllowed = true,
        )

        requireNotNull(restoration)
        assertEquals(anchor.mediaId, restoration.mediaId)
        assertEquals(6, restoration.scrollOffsetPx)
    }

    private fun gridItems(vararg mediaItems: MediaItem): List<TimelineGridItem> =
        mediaItems.mapIndexed { index, item ->
            TimelineSlot(
                key = TimelineSlotKey(dayId = item.dayId, indexInDay = index),
                dayId = item.dayId,
                indexInDay = index,
                mediaItem = item,
            )
        }.toTimelineGridItems()

    private fun List<TimelineGridItem>.indexOfMedia(mediaId: MediaId): Int =
        indexOfFirst { item ->
            item is TimelineGridItem.Slot && item.slot.mediaItem?.mediaId == mediaId
        }

    private fun mediaItem(
        id: String,
        dayId: Int,
        takenAtEpochSeconds: Long,
    ) = MediaItem(
        mediaId = MediaId(id),
        remoteFileId = id.hashCode().toLong(),
        dayId = dayId,
        day = LocalDate.ofEpochDay(dayId.toLong()),
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 1_024,
        height = 768,
        etag = null,
        livePhotoId = null,
        auid = "auid-$id",
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = takenAtEpochSeconds,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(id.hashCode().toLong()),
    )
}
