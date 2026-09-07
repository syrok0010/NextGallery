package com.syrok0010.nextgallery.ui.detail

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaDetailScreenTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun filmstripSelectsPhotoAndBackReturnsCurrentItem() {
        val items = listOf(mediaItem("first"), mediaItem("second"))
        var current: MediaItem? = null
        var closed: MediaItem? = null
        var range: Pair<Int, Int>? = null
        showViewer(items, onCurrent = { current = it }, onClose = { closed = it }, onRange = { a, b -> range = a to b })

        rule.onNodeWithText("first.jpg").assertIsDisplayed()
        rule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        rule.onNodeWithText("second.jpg").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(items[1], current)
            assertEquals(21 to 181, range)
        }
        saveScreenshot("photo")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_back)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(items[1], closed) }
    }

    @Test
    fun videoPlaceholderSupportsDragDismissWithoutTile() {
        val item = mediaItem("video", isVideo = true)
        var closed: MediaItem? = null
        showViewer(listOf(item), tileBounds = null, onClose = { closed = it })

        rule.onNodeWithText(rule.activity.getString(R.string.media_video_badge)).assertIsDisplayed()
        saveScreenshot("video")
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(item, closed) }
    }

    private fun showViewer(
        items: List<MediaItem>,
        tileBounds: Rect? = Rect(30f, 60f, 150f, 180f),
        onCurrent: (MediaItem) -> Unit = {},
        onClose: (MediaItem) -> Unit,
        onRange: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val sequence = ViewerSequence(
            items = items,
            pageIndexByMediaId = items.mapIndexed { index, item -> item.mediaId to index }.toMap(),
            timelineSlotIndexByMediaId = items.mapIndexed { index, item -> item.mediaId to index + 100 }.toMap(),
        )
        rule.setContent {
            MediaDetailScreen(
                initialMediaId = items.first().mediaId,
                sequence = sequence,
                tileBoundsForMediaId = { tileBounds },
                onBack = onClose,
                onCurrentItemChange = onCurrent,
                onVisibleTimelineRange = onRange,
            )
        }
        rule.waitForIdle()
    }

    private fun mediaItem(name: String, isVideo: Boolean = false): MediaItem {
        val file = File(rule.activity.cacheDir, "$name.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(60, 100, 160))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return MediaItem(
            mediaId = MediaId(name), remoteFileId = null, dayId = 20_000,
            day = LocalDate.ofEpochDay(20_000), displayName = "$name.jpg",
            mimeType = if (isVideo) "video/mp4" else "image/jpeg",
            width = 800, height = 600, etag = null, livePhotoId = null,
            auid = null, buid = null, sharedBy = null, takenAtEpochSeconds = null,
            isVideo = isVideo, videoDurationSeconds = null, isFavorite = false, isHidden = false,
            assetRef = MediaAssetRef.LocalContent(file.toURI().toString(), 123),
        )
    }

    private fun saveScreenshot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(rule.activity.getExternalFilesDir(null), "detail-refactor").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
