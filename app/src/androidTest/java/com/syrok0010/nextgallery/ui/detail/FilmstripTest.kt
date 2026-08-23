package com.syrok0010.nextgallery.ui.detail

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
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

    private fun mediaItem(
        id: String,
        dayId: Int,
    ) = MediaItem(
        mediaId = MediaId(id),
        remoteFileId = id.hashCode().toLong(),
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
        assetRef = MediaAssetRef.MemoriesFile(id.hashCode().toLong()),
    )
}
