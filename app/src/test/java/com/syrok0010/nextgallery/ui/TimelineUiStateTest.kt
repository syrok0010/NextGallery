package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineUiStateTest {
    @Test
    fun `refresh retains only pending days that still exist and remain unloaded`() {
        val refreshedSnapshot = snapshot(
            items = listOf(mediaItem(fileId = 1, etag = "same")),
        )
        val state = TimelineUiState(
            loadingDayIds = setOf(DAY_ID, DAY_ID + 1),
            failedDayIds = setOf(DAY_ID, DAY_ID + 1),
        )

        val refreshedState = state.withRefreshedSnapshot(refreshedSnapshot)

        assertEquals(emptySet<Int>(), refreshedState.loadingDayIds)
        assertEquals(emptySet<Int>(), refreshedState.failedDayIds)
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
            mediaId = MediaId("remote-$fileId"),
            remoteFileId = fileId,
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

    private companion object {
        const val DAY_ID = 20_645
    }
}
