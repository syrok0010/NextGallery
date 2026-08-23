package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerTimelineTest {
    @Test
    fun `projection keeps media order and original slot indexes`() {
        val first = mediaItem(mediaId = MediaId("media-first"), fileId = 11)
        val second = mediaItem(mediaId = MediaId("media-second"), fileId = 22)
        val snapshot = snapshot(
            mediaItems = listOf(first, null, second),
        )

        val projection = snapshot.toViewerTimeline()

        assertEquals(listOf(first, second), projection.items)
        assertEquals(
            mapOf(MediaId("media-first") to 0, MediaId("media-second") to 2),
            projection.slotIndexByMediaId,
        )
    }

    @Test
    fun `null snapshot creates an empty projection`() {
        val snapshot: TimelineSnapshot? = null

        val projection = snapshot.toViewerTimeline()

        assertEquals(emptyList<MediaItem>(), projection.items)
        assertEquals(emptyMap<MediaId, Int>(), projection.slotIndexByMediaId)
    }

    @Test
    fun `projection reflects media added by hydration`() {
        val loadedItem = mediaItem(mediaId = MediaId("media-loaded"), fileId = 11)
        val hydratedItem = mediaItem(mediaId = MediaId("media-hydrated"), fileId = 22)
        val initialSnapshot = snapshot(listOf(loadedItem, null))
        val hydratedSnapshot = snapshot(listOf(loadedItem, hydratedItem))

        val projection = hydratedSnapshot.toViewerTimeline()

        assertEquals(listOf(loadedItem, hydratedItem), projection.items)
        assertEquals(
            mapOf(MediaId("media-loaded") to 0, MediaId("media-hydrated") to 1),
            projection.slotIndexByMediaId,
        )
        assertEquals(listOf(loadedItem), initialSnapshot.toViewerTimeline().items)
    }

    @Test
    fun `same remote file id with different media ids keeps both viewer identities`() {
        val first = mediaItem(mediaId = MediaId("media-first"), fileId = 11)
        val duplicate = first.copy(
            mediaId = MediaId("media-second"),
            displayName = "duplicate",
        )

        val projection = snapshot(listOf(first, null, duplicate)).toViewerTimeline()

        assertEquals(listOf(first, duplicate), projection.items)
        assertEquals(
            mapOf(MediaId("media-first") to 0, MediaId("media-second") to 2),
            projection.slotIndexByMediaId,
        )
    }

    private fun snapshot(mediaItems: List<MediaItem?>): TimelineSnapshot {
        val slots = mediaItems.mapIndexed { index, mediaItem ->
            TimelineSlot(
                key = TimelineSlotKey(dayId = DAY_ID, indexInDay = index),
                dayId = DAY_ID,
                indexInDay = index,
                mediaItem = mediaItem,
            )
        }
        return TimelineSnapshot(
            config = MemoriesConfig(
                version = "8.0.1",
                timelinePath = "/Photos",
                albumsEnabled = false,
                recognizeEnabled = false,
                faceRecognitionEnabled = false,
                previewGeneratorEnabled = false,
                stackRawFiles = false,
                dedupIdentical = false,
            ),
            days = listOf(TimelineDay(dayId = DAY_ID, count = slots.size)),
            slots = slots,
            loadedDayIds = emptySet(),
            totalDayCount = 1,
            totalMediaCountHint = slots.size,
        )
    }

    private fun mediaItem(mediaId: MediaId, fileId: Long): MediaItem {
        return MediaItem(
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
    }

    private companion object {
        const val DAY_ID = 20_645
    }
}
