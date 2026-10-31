package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaCollectionSelectionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun actionReceivesSelectionSnapshotAndKeepsSelectionUntilDone() {
        var received = emptyList<MediaItem>()
        var done: (() -> Unit)? = null
        show { items, onDone ->
            received = items
            done = onDone
        }
        rule.onNodeWithContentDescription("first.jpg").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("second.jpg").performClick()
        rule.onNodeWithText("Действие").performClick()
        rule.runOnIdle { assertEquals(listOf(first, second), received) }
        rule.onNodeWithContentDescription("Отменить выбор").assertExists()
        rule.onNodeWithContentDescription("second.jpg").performClick()
        rule.runOnIdle {
            assertEquals(listOf(first, second), received)
            done!!()
        }
        rule.onNodeWithContentDescription("Отменить выбор").assertDoesNotExist()
    }

    @Test fun blockingHandoffDisablesCancellationUntilDone() {
        var done: (() -> Unit)? = null
        show(blocks = true) { _, onDone -> done = onDone }
        rule.onNodeWithContentDescription("first.jpg").performTouchInput { longClick() }
        rule.onNodeWithText("Действие").performClick()
        rule.onNodeWithText("Действие").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Отменить выбор").assertIsNotEnabled()
        rule.runOnIdle { done!!() }
        rule.onNodeWithContentDescription("Отменить выбор").assertDoesNotExist()
    }

    private fun show(blocks: Boolean = false, perform: (List<MediaItem>, () -> Unit) -> Unit) {
        rule.setContent {
            NextGalleryTheme {
                MediaCollectionScreen(
                    slots = listOf(first, second).mapIndexed { index, item ->
                        MediaSlot(MediaSlotKey(item.dayId, index), item.dayId, index, item)
                    },
                    emptyContent = {},
                    selectionEnabled = true,
                    selectionActions = listOf(
                        SelectionAction(
                            ColorPainter(Color.Black),
                            "Действие",
                            isPrimary = true,
                            blocksSelectionUntilDone = blocks,
                            perform = perform,
                        ),
                    ),
                    scaffold = { _, content -> content() },
                )
            }
        }
    }

    private val first = MediaItem(
        mediaId = MediaId("selection-first"), dayId = 20660, displayName = "first.jpg",
        mimeType = "image/jpeg", width = 100, height = 100, etag = null, livePhotoId = null,
        auid = null, buid = null, sharedBy = null, takenAtEpochSeconds = null, isVideo = false,
        videoDurationSeconds = null, isFavorite = false, isHidden = false,
        assetRef = MediaAssetRef.LocalContent("content://selection/first", null),
    )
    private val second = first.copy(
        mediaId = MediaId("selection-second"),
        displayName = "second.jpg",
    )
}
