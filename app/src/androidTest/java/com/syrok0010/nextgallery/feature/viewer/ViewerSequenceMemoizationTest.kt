package com.syrok0010.nextgallery.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerSequenceMemoizationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun unrelatedStateKeepsSequenceAndHydrationRebuildsIt() {
        val initialSnapshot = snapshot(null)
        val hydratedItem = mediaItem("hydrated", fileId = 22)
        val snapshotState = mutableStateOf(initialSnapshot)
        val unrelatedState = mutableIntStateOf(0)
        val currentSequence = AtomicReference<ViewerSequence>()

        composeRule.setContent {
            val unrelatedValue = unrelatedState.intValue
            val sequence = rememberViewerSequence(snapshotState.value, currentMediaId = null)
            SideEffect {
                check(unrelatedValue >= 0)
                currentSequence.set(sequence)
            }
        }
        composeRule.waitForIdle()
        val initialSequence = currentSequence.get()

        composeRule.runOnIdle { unrelatedState.intValue++ }
        composeRule.waitForIdle()
        assertSame(initialSequence, currentSequence.get())

        composeRule.runOnIdle { snapshotState.value = snapshot(hydratedItem) }
        composeRule.waitForIdle()

        assertNotSame(initialSequence, currentSequence.get())
        assertEquals(listOf(hydratedItem.mediaId), currentSequence.get().items.map { it.mediaId })
    }

    @Test
    fun reorderAndCurrentMergeKeepTheSamePagerSurface() {
        val first = mediaItem("first", 11)
        val current = mediaItem("current", 22)
        val last = mediaItem("last", 33)
        val mergedCurrent = current.copy(
            displayName = "merged-current.jpg",
            assetRef = MediaAssetRef.LocalFirst(
                local = MediaAssetRef.LocalContent(
                    contentUri = "content://media/current",
                    modifiedAtEpochSeconds = 123,
                ),
                remote = MediaAssetRef.MemoriesFile(photoFileId = 22),
            ),
        )
        val snapshotState = mutableStateOf(snapshot(first, current, last))
        val currentMediaIdState = mutableStateOf(current.mediaId)
        val currentSequence = AtomicReference<ViewerSequence>()
        val pagerState = AtomicReference<PagerState>()
        val observedCurrentMediaId = AtomicReference<MediaId>()
        val surfaceCompositions = ConcurrentHashMap<MediaId, Int>()

        composeRule.setContent {
            val sequence = rememberViewerSequence(
                snapshot = snapshotState.value,
                currentMediaId = currentMediaIdState.value,
            )
            val state = rememberPagerState(
                initialPage = checkNotNull(sequence.pageIndex(current.mediaId)),
            ) { sequence.items.size }
            SideEffect {
                currentSequence.set(sequence)
                pagerState.set(state)
            }
            LaunchedEffect(state.currentPage, sequence.items) {
                observedCurrentMediaId.set(sequence.items[state.currentPage].mediaId)
            }
            HorizontalPager(
                state = state,
                key = sequence::pageKey,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                val item = sequence.items[page]
                DisposableEffect(item.mediaId) {
                    surfaceCompositions.compute(item.mediaId) { _, count -> (count ?: 0) + 1 }
                    onDispose { }
                }
                Box(Modifier.fillMaxSize())
            }
        }
        composeRule.waitForIdle()
        assertEquals(1, surfaceCompositions[current.mediaId])

        composeRule.runOnIdle {
            snapshotState.value = snapshot(last, first, mergedCurrent)
        }
        composeRule.waitUntil {
            pagerState.get().currentPage == 2 &&
                currentSequence.get().items[pagerState.get().currentPage].mediaId == current.mediaId
        }

        assertEquals("merged-current.jpg", currentSequence.get().item(current.mediaId)?.displayName)
        assertEquals(current.mediaId, observedCurrentMediaId.get())
        assertEquals(1, surfaceCompositions[current.mediaId])
    }

    @Test
    fun orphanCurrentIsRetainedUntilCurrentMediaChanges() {
        val current = mediaItem("current", 22)
        val next = mediaItem("next", 33)
        val snapshotState = mutableStateOf(snapshot(current, next))
        val currentMediaIdState = mutableStateOf(current.mediaId)
        val currentSequence = AtomicReference<ViewerSequence>()

        composeRule.setContent {
            val sequence = rememberViewerSequence(
                snapshot = snapshotState.value,
                currentMediaId = currentMediaIdState.value,
            )
            SideEffect { currentSequence.set(sequence) }
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle { snapshotState.value = snapshot(next) }
        composeRule.waitForIdle()
        assertEquals(listOf(current, next), currentSequence.get().items)

        composeRule.runOnIdle { currentMediaIdState.value = next.mediaId }
        composeRule.waitForIdle()
        assertEquals(listOf(next), currentSequence.get().items)
    }

    private fun snapshot(vararg mediaItems: MediaItem?): TimelineSnapshot {
        val slots = mediaItems.mapIndexed { index, item ->
            TimelineSlot(
                key = TimelineSlotKey(dayId = DAY_ID, indexInDay = index),
                dayId = DAY_ID,
                indexInDay = index,
                mediaItem = item,
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

    private fun mediaItem(id: String, fileId: Long): MediaItem = MediaItem(
        mediaId = MediaId("media-$id"),
        remoteFileId = fileId,
        dayId = DAY_ID,
        day = LocalDate.ofEpochDay(DAY_ID.toLong()),
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
