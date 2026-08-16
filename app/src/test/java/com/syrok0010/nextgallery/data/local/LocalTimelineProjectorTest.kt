package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalTimelineProjectorTest {
    @Test
    fun `projects local only tile into authorized remote timeline and viewer order`() {
        val remote = TimelineSnapshotAssembler.assemble(
            config = config(),
            days = listOf(TimelineDay(dayId = 2, count = 1)),
            mediaItems = listOf(remoteItem()),
            loadedDayIds = setOf(2),
        )
        val local = localItem(dayId = 3)

        val projected = LocalTimelineProjector.project(remote, listOf(local))

        assertEquals(listOf(3, 2), projected.days.map { it.dayId })
        assertEquals(listOf(local.mediaId, MediaId("remote")), projected.items.map { it.mediaId })
        assertFalse(projected.items.first().assetRef is MediaAssetRef.MemoriesFile)
        assertTrue(projected.items.last().assetRef is MediaAssetRef.MemoriesFile)
        assertEquals(2, projected.totalMediaCountHint)
    }

    @Test
    fun `keeps remote placeholders while adding local items to the same day`() {
        val remote = TimelineSnapshotAssembler.assemble(
            config = config(),
            days = listOf(TimelineDay(dayId = 3, count = 2)),
        )

        val projected = LocalTimelineProjector.project(remote, listOf(localItem(dayId = 3)))

        assertEquals(3, projected.slots.size)
        assertEquals(1, projected.items.size)
        assertEquals(2, projected.slots.count { it.mediaItem == null })
    }

    private fun remoteItem() = item(
        mediaId = MediaId("remote"),
        fileId = 1,
        dayId = 2,
        timestamp = 180_000,
        assetRef = MediaAssetRef.MemoriesFile(1),
    )

    private fun localItem(dayId: Int) = item(
        mediaId = MediaId("local"),
        fileId = 2,
        dayId = dayId,
        timestamp = dayId * 86_400L,
        assetRef = MediaAssetRef.LocalContent("content://images/2"),
    )

    private fun item(
        mediaId: MediaId,
        fileId: Long,
        dayId: Int,
        timestamp: Long,
        assetRef: MediaAssetRef,
    ) = MediaItem(
        mediaId = mediaId,
        fileId = fileId,
        dayId = dayId,
        day = LocalDate.ofEpochDay(dayId.toLong()),
        displayName = mediaId.value,
        mimeType = "image/jpeg",
        width = 100,
        height = 100,
        etag = null,
        livePhotoId = null,
        auid = null,
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = timestamp,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = assetRef,
    )

    private fun config() = MemoriesConfig(
        version = "test",
        timelinePath = null,
        albumsEnabled = false,
        recognizeEnabled = false,
        faceRecognitionEnabled = false,
        previewGeneratorEnabled = false,
        stackRawFiles = false,
        dedupIdentical = false,
    )
}
