package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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
        val provider = VideoFrameProvider {
            kotlinx.coroutines.flow.flow {
                emit(VideoFrameEvent.Duration(12_000))
                emit(VideoFrameEvent.Frame(0, android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)))
                throw java.io.IOException("temporary frame failure")
            }
        }
        composeRule.setContent {
            Filmstrip(items, currentPage.intValue, { currentPage.intValue = it },
                onVideoScrub = { position, finished -> seeks += position to finished }, frameProvider = provider)
        }
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoFilmstripRetryTestTag).assertIsDisplayed()
        val strip = composeRule.onNodeWithTag(VideoFilmstripTestTag).fetchSemanticsNode()
        val range = strip.config[androidx.compose.ui.semantics.SemanticsProperties.HorizontalScrollAxisRange]
        assertEquals(strip.boundsInRoot.width * VideoFilmstripScreenWidths, range.maxValue(), 2f)
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag(VideoFilmstripTestTag).performTouchInput {
            swipeWithVelocity(Offset(width * 0.45f, height * 0.8f), Offset(width * 0.05f, height * 0.8f), 1500f, 200)
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
            assertTrue(seeks.isNotEmpty())
            assertTrue(seeks.last().second)
            assertTrue(seeks.last().first > positionAtRelease)
        }
        composeRule.onNodeWithTag(VideoFilmstripRetryTestTag).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag).assertIsDisplayed()
        val expandedWidth = composeRule.onNodeWithTag(filmstripTileTestTag(1)).fetchSemanticsNode().boundsInRoot.width
        composeRule.mainClock.autoAdvance = false
        composeRule.onNodeWithTag("video_filmstrip_collapse").performClick()
        composeRule.mainClock.advanceTimeBy(100)
        val middleWidth = composeRule.onNodeWithTag(filmstripTileTestTag(1)).fetchSemanticsNode().boundsInRoot.width
        assertTrue(middleWidth > 60 && middleWidth < expandedWidth)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(VideoFilmstripTestTag).assertDoesNotExist()
        composeRule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        composeRule.onNodeWithTag(VideoFilmstripTestTag).assertIsDisplayed()
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
