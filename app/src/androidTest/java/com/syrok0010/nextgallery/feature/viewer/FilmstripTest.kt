package com.syrok0010.nextgallery.ui.detail

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
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
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

    private fun mediaItem(
        id: String,
        dayId: Int,
    ) = MediaItem(
        mediaId = MediaId(id),
        remoteFileId = null,
        dayId = dayId,
        day = LocalDate.ofEpochDay(dayId.toLong()),
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
