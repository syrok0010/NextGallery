package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineUiStateTest {
    @Test
    fun `refresh preserves only thumbnails whose file and etag are unchanged`() {
        val previousSnapshot = snapshot(
            items = listOf(
                mediaItem(fileId = 1, etag = "same"),
                mediaItem(fileId = 2, etag = "old"),
            ),
        )
        val refreshedSnapshot = snapshot(
            items = listOf(
                mediaItem(fileId = 1, etag = "same"),
                mediaItem(fileId = 2, etag = "new"),
            ),
        )
        val state = TimelineUiState(
            snapshot = previousSnapshot,
            thumbnailPreviews = mapOf(1L to thumbnail(1), 2L to thumbnail(2)),
            thumbnailLoadingFileIds = setOf(1, 2),
            thumbnailFailedFileIds = setOf(1, 2),
        )

        val refreshedState = state.withRefreshedSnapshot(refreshedSnapshot)

        assertEquals(setOf(1L), refreshedState.thumbnailPreviews.keys)
        assertEquals(setOf(1L), refreshedState.thumbnailLoadingFileIds)
        assertTrue(refreshedState.thumbnailFailedFileIds.isEmpty())
    }

    private fun snapshot(items: List<MediaItem>): TimelineSnapshot {
        return TimelineSnapshotAssembler.assemble(
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
            days = listOf(TimelineDay(dayId = DAY_ID, count = items.size)),
            mediaItems = items,
            loadedDayIds = setOf(DAY_ID),
        )
    }

    private fun mediaItem(fileId: Long, etag: String): MediaItem {
        return MediaItem(
            fileId = fileId,
            dayId = DAY_ID,
            day = LocalDate.ofEpochDay(DAY_ID.toLong()),
            displayName = "file-$fileId",
            mimeType = "image/jpeg",
            width = 512,
            height = 512,
            etag = etag,
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

    private fun thumbnail(fileId: Long): ThumbnailPreview {
        return ThumbnailPreview(
            fileId = fileId,
            requestId = fileId.toInt(),
            mimeType = "image/jpeg",
            bytes = byteArrayOf(fileId.toByte()),
        )
    }

    private companion object {
        const val DAY_ID = 20_645
    }
}
