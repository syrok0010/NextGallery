package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerTimelineMemoizationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unrelatedStateKeepsProjectionAndHydrationRebuildsIt() {
        val initialSnapshot = snapshot(mediaItem = null)
        val hydratedSnapshot = snapshot(
            mediaItem = mediaItem(mediaId = MediaId("hydrated-media"), fileId = 22),
        )
        val snapshotState = mutableStateOf(initialSnapshot)
        val unrelatedState = mutableIntStateOf(0)
        val currentProjection = AtomicReference<ViewerTimeline>()

        composeRule.setContent {
            val unrelatedValue = unrelatedState.intValue
            val projection = rememberViewerTimeline(snapshotState.value)
            SideEffect {
                check(unrelatedValue >= 0)
                currentProjection.set(projection)
            }
        }
        composeRule.waitForIdle()
        val initialProjection = currentProjection.get()

        composeRule.runOnIdle {
            unrelatedState.intValue++
        }
        composeRule.waitForIdle()

        assertSame(initialProjection, currentProjection.get())

        composeRule.runOnIdle {
            snapshotState.value = hydratedSnapshot
        }
        composeRule.waitForIdle()

        assertNotSame(initialProjection, currentProjection.get())
        assertEquals(
            listOf(MediaId("hydrated-media")),
            currentProjection.get().items.map { it.mediaId },
        )
    }

    private fun snapshot(mediaItem: MediaItem?): TimelineSnapshot {
        val slot = TimelineSlot(
            key = TimelineSlotKey(dayId = DAY_ID, indexInDay = 0),
            dayId = DAY_ID,
            indexInDay = 0,
            mediaItem = mediaItem,
        )
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
            days = listOf(TimelineDay(dayId = DAY_ID, count = 1)),
            slots = listOf(slot),
            loadedDayIds = emptySet(),
            totalDayCount = 1,
            totalMediaCountHint = 1,
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
