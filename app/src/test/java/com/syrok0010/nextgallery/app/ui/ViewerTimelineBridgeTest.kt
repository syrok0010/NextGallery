package com.syrok0010.nextgallery.app.ui

import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSlot
import com.syrok0010.nextgallery.feature.timeline.TimelineSlotKey
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ViewerTimelineBridgeTest {
    @Test fun `prefetch uses timeline slots with placeholders and excludes orphan media`() {
        val current = mediaItem("current", 1)
        val orphan = mediaItem("orphan", 2)
        val index = ViewerTimelineIndex(snapshot(*(List<MediaItem?>(101) { null } + current).toTypedArray()))
        assertEquals(0..341, index.prefetchRange(current.mediaId))
        assertEquals(null, index.prefetchRange(orphan.mediaId))
    }

    @Test
    fun `null snapshot creates an empty sequence`() {
        val sequence = (null as TimelineSnapshot?).toViewerSequence()

        assertSame(ViewerSequence.Empty, sequence)
    }

    @Test
    fun `projection keeps media order and timeline slot indexes`() {
        val first = mediaItem("first", fileId = 11)
        val second = mediaItem("second", fileId = 22)

        val sequence = snapshot(first, null, second).toViewerSequence()

        assertEquals(listOf(first, second), sequence.items)
        assertEquals(0, sequence.pageIndex(first.mediaId))
        assertEquals(1, sequence.pageIndex(second.mediaId))
        assertEquals(0, ViewerTimelineIndex(snapshot(first, null, second)).slotIndex(first.mediaId))
        assertEquals(2, ViewerTimelineIndex(snapshot(first, null, second)).slotIndex(second.mediaId))
    }

    @Test
    fun `live reorder changes neighbors while retaining current identity`() {
        val controller = ViewerSequenceController()
        val first = mediaItem("first", 11)
        val current = mediaItem("current", 22)
        val last = mediaItem("last", 33)
        controller.update(snapshot(first, current, last), current.mediaId)

        val updated = controller.update(snapshot(last, current, first), current.mediaId)

        assertEquals(listOf(last, current, first), updated.items)
        assertEquals(1, updated.pageIndex(current.mediaId))
    }

    @Test
    fun `same remote file with different media ids keeps both viewer identities`() {
        val first = mediaItem("first", 11)
        val second = first.copy(
            mediaId = MediaId("media-second"),
            displayName = "second.jpg",
        )

        val sequence = snapshot(first, null, second).toViewerSequence()

        assertEquals(listOf(first.mediaId, second.mediaId), sequence.items.map { it.mediaId })
    }

    @Test
    fun `merge updates current media without changing its identity`() {
        val controller = ViewerSequenceController()
        val current = mediaItem("current", 22)
        controller.update(snapshot(current), current.mediaId)
        val merged = current.copy(
            displayName = "remote-current.jpg",
            assetRef = MediaAssetRef.LocalFirst(
                local = MediaAssetRef.LocalContent(
                    contentUri = "content://media/current",
                    modifiedAtEpochSeconds = 123,
                ),
                remote = MediaAssetRef.MemoriesFile(photoFileId = 22),
            ),
        )

        val updated = controller.update(snapshot(merged), current.mediaId)

        assertSame(merged, updated.item(current.mediaId))
        assertEquals(current.mediaId.value, updated.pageKey(0))
    }

    @Test
    fun `removed current is retained beside its live neighbor`() {
        val controller = ViewerSequenceController()
        val first = mediaItem("first", 11)
        val current = mediaItem("current", 22)
        val next = mediaItem("next", 33)
        controller.update(snapshot(first, current, next), current.mediaId)

        val updated = controller.update(snapshot(first, next), current.mediaId)

        assertEquals(listOf(first, current, next), updated.items)
        assertEquals(null, ViewerTimelineIndex(snapshot(first, next)).slotIndex(current.mediaId))
    }

    @Test
    fun `removed current remains as the only page when no neighbors survive`() {
        val controller = ViewerSequenceController()
        val current = mediaItem("current", 22)
        controller.update(snapshot(current), current.mediaId)

        val updated = controller.update(snapshot(), current.mediaId)

        assertEquals(listOf(current), updated.items)
    }

    @Test
    fun `leaving removed current drops it from sequence`() {
        val controller = ViewerSequenceController()
        val current = mediaItem("current", 22)
        val next = mediaItem("next", 33)
        controller.update(snapshot(current, next), current.mediaId)
        controller.update(snapshot(next), current.mediaId)

        val updated = controller.update(snapshot(next), next.mediaId)

        assertEquals(listOf(next), updated.items)
    }

    @Test
    fun `changing current reuses cached live projection`() {
        val controller = ViewerSequenceController()
        val first = mediaItem("first", 11)
        val second = mediaItem("second", 22)
        val source = snapshot(first, second)
        val initial = controller.update(source, first.mediaId)

        val updated = controller.update(source, second.mediaId)

        assertSame(initial, updated)
    }

    private fun snapshot(vararg mediaItems: MediaItem?): TimelineSnapshot {
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

            totalMediaCountHint = slots.size,
        )
    }

    private fun mediaItem(id: String, fileId: Long): MediaItem = MediaItem(
        mediaId = MediaId("media-$id"),
        dayId = DAY_ID,
        displayName = "$id.jpg",
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
