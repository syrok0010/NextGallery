package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.di.MediaTestContext
import com.syrok0010.nextgallery.feature.collection.MediaSlot
import com.syrok0010.nextgallery.feature.collection.MediaSlotKey
import com.syrok0010.nextgallery.feature.collection.rememberViewerSequence
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ViewerHydrationAnchorTest {
    @get:Rule val rule = createComposeRule()

    @Test fun loadingNewerDaysDoesNotChangeTheOpenedPhoto() {
        val photos = (0 until 401).map(::photo)
        val target = photos[390]
        val slots =
            mutableStateOf(
                photos.mapIndexed { index, item ->
                    slot(
                        index,
                        item.takeIf {
                            index < 10 || index >= 380
                        },
                    )
                },
            )
        val current = mutableStateOf(target.mediaId)
        val deleted = mutableStateOf(emptySet<MediaId>())
        val observed = CopyOnWriteArrayList<MediaId>()
        rule.setContent {
            MediaTestContext {
                val sequence = rememberViewerSequence(slots.value, current.value, deleted.value)
                MaterialTheme {
                    MediaDetailScreen(
                        initialMediaId = current.value,
                        sequence = sequence,
                        tileBoundsForMediaId = { null },
                        onBack = {},
                        onCurrentItemChange = {
                            observed += it.mediaId
                            current.value = it.mediaId
                        },
                    )
                }
            }
        }
        rule.waitForIdle()
        assertEquals(target.mediaId, current.value)
        for (loadedUntil in listOf(70, 130, 190, 250, 310, 380)) {
            rule.runOnIdle {
                slots.value = photos.mapIndexed { index, item ->
                    slot(
                        index,
                        item.takeIf {
                            index < loadedUntil || index >= 380
                        },
                    )
                }
            }
            rule.waitForIdle()
            assertEquals("Loaded newer days up to $loadedUntil", target.mediaId, current.value)
        }
        assertEquals(
            "Hydration must never report a different photo, even temporarily",
            setOf(target.mediaId),
            observed.toSet(),
        )
        rule.onNodeWithText(target.displayName).assertIsDisplayed()

        // Ordinary navigation must still work once hydration has shifted every index.
        rule.onRoot().performTouchInput { swipeLeft() }
        rule.waitUntil(5_000) { current.value == photos[391].mediaId }
        rule.onNodeWithTag(filmstripTileTestTag(392)).performClick()
        rule.waitUntil(5_000) { current.value == photos[392].mediaId }

        rule.runOnIdle {
            slots.value = slots.value.filterNot { it.mediaItem?.mediaId == photos[392].mediaId }
            deleted.value = setOf(photos[392].mediaId)
        }
        rule.waitUntil(5_000) { current.value == photos[393].mediaId }
        rule.onNodeWithText(photos[393].displayName).assertIsDisplayed()
    }

    private fun slot(index: Int, item: MediaItem?) =
        MediaSlot(MediaSlotKey(20_000 - index, 0), 20_000 - index, 0, item)

    private fun photo(index: Int) =
        MediaItem(
            mediaId = MediaId(
                "hydration-$index",
            ),
            dayId = 20_000 - index, displayName = "photo-$index.jpg",
            mimeType = "image/jpeg", width = 100, height = 100, etag = null, livePhotoId = null,
            auid = null, buid = null, sharedBy = null, takenAtEpochSeconds = null, isVideo = false,
            videoDurationSeconds = null, isFavorite = false, isHidden = false,
            assetRef = MediaAssetRef.LocalContent("content://unloaded/photo-$index", null, 1),
        )
}
