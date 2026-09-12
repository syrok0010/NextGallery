package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
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
                items = items,
                currentPage = currentPage.intValue,
                onPageSelected = { index ->
                    selectedPage = index
                    currentPage.intValue = index
                },
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
                items = items,
                currentPage = currentPage.intValue,
                onPageSelected = { currentPage.intValue = it },
                lazyListState = listState,
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
            assertTrue("Fling should continue selecting later photos", currentPage.intValue > pageAtRelease + 1)
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
            override fun sourceFor(id: MediaId): String? = null
            override fun seek(id: MediaId, position: Long, finished: Boolean) {
                if (items[currentPage.intValue].mediaId == id) seeks += position to finished
            }
            override fun finish(id: MediaId) = Unit
        }
        val listState = LazyListState()
        val provider = VideoFrameProvider {
            kotlinx.coroutines.flow.flow {
                emit(VideoFrameEvent.Duration(12_000))
                emit(VideoFrameEvent.Frame(0, android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)))
                throw java.io.IOException("temporary frame failure")
            }
        }
        composeRule.setContent {
            Filmstrip(items, currentPage.intValue, { currentPage.intValue = it },
                playback = playback, frameProvider = provider, lazyListState = listState)
        }
        composeRule.waitForIdle()
        val initialLeft = listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.offset
        val neighborLeft = listState.layoutInfo.visibleItemsInfo.first { it.index == 2 }.offset
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.mainClock.advanceTimeBy(100)
        composeRule.runOnIdle {
            assertEquals(initialLeft, listState.layoutInfo.visibleItemsInfo.first { it.index == 1 }.offset)
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
            swipeWithVelocity(Offset(width * 0.7f, height * 0.8f), Offset(width * 0.3f, height * 0.8f), 1500f, 200)
        }
        composeRule.mainClock.advanceTimeByFrame()
        var positionAtRelease = 0L
        composeRule.runOnIdle { positionAtRelease = seeks.last().first }
        composeRule.mainClock.advanceTimeBy(250)
        composeRule.runOnIdle {
            assertTrue("Fling continues seeking after release", seeks.last().first > positionAtRelease)
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
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoFilmstripTestTag, useUnmergedTree = true).performSemanticsAction(
            androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1f) }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FilmstripTestTag).performTouchInput { swipeLeft() }
        composeRule.runOnIdle { assertEquals(2, currentPage.intValue) }
        composeRule.onNodeWithTag(filmstripTileTestTag(2)).assertIsDisplayed()
    }

    private fun mediaItem(
        id: String,
        dayId: Int,
    ) = MediaItem(
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
        ),
    )
}
