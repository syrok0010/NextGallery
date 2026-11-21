package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeWithVelocity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FilmstripTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun filmstripDisplaysThumbnailsAndHandlesTapSelection() {
        val items = (0 until 5).map { index ->
            mediaItem(id = "item-$index", dayId = 20_000 - index)
        }
        val currentPage = mutableIntStateOf(0)
        var selectedPage = -1

        composeRule.setContent {
            Filmstrip(
                state = rememberViewerFilmstripState(items, items[currentPage.intValue].mediaId, { id ->
                    val index = items.indexOfFirst { it.mediaId == id }
                    selectedPage = index
                    currentPage.intValue = index
                }),
            )
        }

        composeRule.onNodeWithTag(FilmstripTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(filmstripTileTestTag(0)).assertIsDisplayed()
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).assertIsDisplayed()

        // Tap item 2
        composeRule.onNodeWithTag(filmstripTileTestTag(2)).performClick()
        composeRule.waitForIdle()

        assertEquals(2, selectedPage)
        assertEquals(2, currentPage.intValue)
    }

    @Test
    fun fastSwipeKeepsSelectingPhotosAfterRelease() {
        val items = (0 until 100).map { mediaItem("item-$it", 20_000 - it) }
        val currentPage = mutableIntStateOf(20)
        val listState = LazyListState(firstVisibleItemIndex = 20)
        composeRule.setContent {
            Filmstrip(
                state = rememberViewerFilmstripState(items, items[currentPage.intValue].mediaId, { id ->
                    currentPage.intValue = items.indexOfFirst { it.mediaId == id }
                }, lazyListState = listState),
            )
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false

        composeRule.onNodeWithTag(FilmstripTestTag).performTouchInput {
            swipeWithVelocity(
                start = Offset(width * 0.8f, height / 2f),
                end = Offset(width * 0.3f, height / 2f),
                endVelocity = 3_000f,
                durationMillis = 200,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        var pageAtRelease = 0
        composeRule.runOnIdle { pageAtRelease = currentPage.intValue }
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.runOnIdle {
            assertTrue(
                "Fling should continue selecting later photos",
                currentPage.intValue > pageAtRelease + 1,
            )
        }

        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(currentPage.intValue, listState.firstVisibleItemIndex)
            assertEquals(0, listState.firstVisibleItemScrollOffset)
        }
    }

    @Test
    fun videoExpandsProgressivelyAndScrubNeverSelectsAnotherMedia() {
        val video = mediaItem("video", 20_000).copy(isVideo = true, videoDurationSeconds = 12)
        val items = listOf(mediaItem("before", 20_001), video, mediaItem("after", 19_999))
        val currentPage = mutableIntStateOf(0)
        val seeks = mutableListOf<Pair<Long, Boolean>>()
        val playback = object : FilmstripPlayback {
            override fun seek(id: MediaId, position: Long, finished: Boolean) {
                if (items[currentPage.intValue].mediaId == id) seeks += position to finished
            }
            override fun finish(id: MediaId) = Unit
        }
        val listState = LazyListState()
        val provider = VideoFrameProvider {
            kotlinx.coroutines.flow.flow {
                emit(VideoFrameEvent.Duration(12_000))
                emit(
                    VideoFrameEvent.Frame(
                        0,
                        android.graphics.Bitmap.createBitmap(
                            16,
                            16,
                            android.graphics.Bitmap.Config.ARGB_8888,
                        ),
                    ),
                )
                throw java.io.IOException("temporary frame failure")
            }
        }
        composeRule.setContent {
            Filmstrip(
                state = rememberViewerFilmstripState(items, items[currentPage.intValue].mediaId, { id ->
                    currentPage.intValue = items.indexOfFirst { it.mediaId == id }
                }, lazyListState = listState),
                playback = playback,
                frameProvider = provider,
                // Seeks are paced by time; the test clock, not the wall clock, decides it here.
                uptimeMillis = { composeRule.mainClock.currentTime },
            )
        }
        composeRule.waitForIdle()
        val initialLeft = listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.offset
        val neighborLeft = listState.layoutInfo.visibleItemsInfo.first { it.index == 2 }.offset
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.runOnIdle {
            assertEquals(
                initialLeft,
                listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.offset,
            )
            assertTrue(listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.size > 100)
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        val videoInfo = listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }
        assertEquals(initialLeft, videoInfo.offset)
        val expectedWidth = with(composeRule.density) { (VideoFilmstripFrameWidth * 7).toPx() }
        assertEquals(expectedWidth, videoInfo.size.toFloat(), 2f)
        assertTrue(videoInfo.offset + videoInfo.size > neighborLeft)
        composeRule.onNodeWithTag(VideoFilmstripRetryTestTag).performClick()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(FilmstripTestTag).performTouchInput {
            swipeWithVelocity(
                Offset(width * 0.7f, height * 0.8f),
                Offset(width * 0.3f, height * 0.8f),
                1500f,
                200,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        var positionAtRelease = 0L
        composeRule.runOnIdle { positionAtRelease = seeks.last().first }
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.runOnIdle {
            assertTrue(
                "Fling continues seeking after release",
                seeks.last().first > positionAtRelease,
            )
            assertEquals(1, currentPage.intValue)
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(1, currentPage.intValue)
            assertTrue(seeks.last().second)
            assertTrue(seeks.last().first > positionAtRelease)
        }
        val expandedWidth = listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.size
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performTouchInput { click() }
        composeRule.mainClock.advanceTimeBy(100)
        val middleWidth = listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.size
        assertTrue(middleWidth > 60 && middleWidth < expandedWidth)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule
            .onNodeWithTag(
                VideoFilmstripTestTag,
                useUnmergedTree = true,
            ).assertDoesNotExist()
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule
            .onNodeWithTag(
                VideoFilmstripTestTag,
                useUnmergedTree = true,
            ).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                it(1f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FilmstripTestTag).performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(2, currentPage.intValue) }
        composeRule.onNodeWithTag(filmstripTileTestTag(2)).assertIsDisplayed()
    }

    @Test
    fun sameLengthReorderDuringFlingKeepsVideoSelectedWhilePlayheadIsInsideIt() {
        val video = mediaItem("video", 20_000).copy(isVideo = true, videoDurationSeconds = 3600)
        val before = mediaItem("before", 20_001)
        val after = mediaItem("after", 19_999)
        val items = mutableStateOf(listOf(before, video, after))
        val current = mutableStateOf(video.mediaId)
        val provider = VideoFrameProvider {
            kotlinx.coroutines.flow.flow { emit(VideoFrameEvent.Duration(3_600_000)) }
        }
        lateinit var state: ViewerFilmstripState
        composeRule.setContent {
            state = rememberViewerFilmstripState(items.value, current.value, { current.value = it })
            Filmstrip(
                state = state,
                frameProvider = provider,
            )
        }
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.8f) }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(FilmstripTestTag).performTouchInput {
            swipeWithVelocity(
                Offset(width * 0.6f, height * 0.8f),
                Offset(width * 0.3f, height * 0.8f),
                3000f,
                200,
            )
        }
        composeRule.mainClock.advanceTimeByFrame()
        composeRule.runOnIdle {
            assertTrue("Reorder must happen during fling", state.isScrolling)
            items.value = listOf(video, before, after)
        }
        composeRule.mainClock.advanceTimeBy(80)
        composeRule.runOnIdle {
            assertTrue("Playhead is still inside expanded video", state.fraction(video.mediaId) in 0.8f..0.99f)
            assertEquals(video.mediaId, current.value)
        }
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
    }

    @Test
    fun hiddenStripRetainsScrollPositionAndCancelsFrameExtraction() {
        val video = mediaItem("video", 20_000).copy(isVideo = true, videoDurationSeconds = 12)
        val items = listOf(video, mediaItem("after", 19_999))
        val shown = mutableStateOf(true)
        var cancellations = 0
        var requests = 0
        val provider = VideoFrameProvider {
            kotlinx.coroutines.flow.flow {
                requests++
                try {
                    emit(VideoFrameEvent.Duration(12_000))
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    cancellations++
                }
            }
        }
        lateinit var state: ViewerFilmstripState
        composeRule.setContent {
            state = rememberViewerFilmstripState(items, video.mediaId, {})
            if (shown.value) Filmstrip(state, frameProvider = provider)
        }
        composeRule.onNodeWithTag(filmstripTileTestTag(0)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0.7f) }
        composeRule.waitForIdle()
        var fraction = 0f
        var offset = 0
        composeRule.runOnIdle {
            fraction = state.fraction(video.mediaId)
            assertEquals(0.7f, fraction, 0.01f)
            offset = state.lazyListState.firstVisibleItemScrollOffset
            shown.value = false
        }
        composeRule.onNodeWithTag(FilmstripTestTag).assertDoesNotExist()
        composeRule.runOnIdle {
            assertEquals(1, cancellations)
            assertEquals(video.mediaId, state.expandedVideo)
            shown.value = true
        }
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(2, requests)
            assertEquals(offset, state.lazyListState.firstVisibleItemScrollOffset)
            assertEquals(fraction, state.fraction(video.mediaId), 0.01f)
        }
    }

    @Test
    fun removingExpandedVideoRestoresAutoCentering() {
        val video = mediaItem("video", 20_000).copy(isVideo = true, videoDurationSeconds = 12)
        val photos = (0 until 60).map { mediaItem("photo-$it", 20_000 - it) }
        val items = mutableStateOf(listOf(video) + photos)
        val currentPage = mutableIntStateOf(0)
        val listState = LazyListState()
        composeRule.setContent {
            Filmstrip(
                state = rememberViewerFilmstripState(items.value, items.value[currentPage.intValue].mediaId, { id ->
                    currentPage.intValue = items.value.indexOfFirst { it.mediaId == id }
                }, lazyListState = listState),
                frameProvider = VideoFrameProvider { kotlinx.coroutines.flow.emptyFlow() },
            )
        }
        composeRule.onNodeWithTag(filmstripTileTestTag(0)).performClick()
        composeRule.onNodeWithTag("video_filmstrip_playhead").assertIsDisplayed()
        composeRule.runOnIdle {
            // The expanded object disappears after the viewer moves to a neighbor.
            items.value = photos
            currentPage.intValue = 40
        }
        composeRule.onNodeWithTag("video_filmstrip_playhead").assertDoesNotExist()
        composeRule.onNodeWithTag(filmstripTileTestTag(40)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(40, listState.firstVisibleItemIndex) }
    }

    private fun mediaItem(id: String, dayId: Int) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = dayId,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 1_024,
            height = 768,
            etag = null,
            livePhotoId = null,
            auid = "auid-$id",
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = 1_728_000_000L,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://filmstrip/$id",
                modifiedAtEpochSeconds = 1_728_000_000L,
                sizeBytes = 1,
            ),
        )
}
