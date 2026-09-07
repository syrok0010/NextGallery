package com.syrok0010.nextgallery.feature.viewer

import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Gainmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        showViewer(items, onCurrent = { current = it }, onClose = { closed = it })

        rule.onNodeWithText("first.jpg").assertIsDisplayed()
        rule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        rule.onNodeWithText("second.jpg").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(items[1], current)
        }
        saveScreenshot("photo")
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.action_back)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(items[1], closed) }
    }

    @Test
    fun localVideoPosterSupportsDragDismissWithoutTile() {
        val item = mediaItem("video", isVideo = true)
        var closed: MediaItem? = null
        showViewer(listOf(item), tileBounds = null, onClose = { closed = it })

        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).assertIsDisplayed()
        saveScreenshot("video")
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(item, closed) }
    }

    @Test
    fun reorderingSequencePreservesActiveItemAndDismissal() {
        val first = mediaItem("first")
        val second = mediaItem("second")
        val prepended = mediaItem("prepended")
        val sequenceState = mutableStateOf(
            ViewerSequence(
                items = listOf(first, second),
                pageIndexByMediaId = mapOf(first.mediaId to 0, second.mediaId to 1),
            ),
        )
        var closed: MediaItem? = null
        rule.setContent {
            MediaDetailScreen(
                initialMediaId = first.mediaId,
                sequence = sequenceState.value,
                tileBoundsForMediaId = { null },
                onBack = { closed = it },
                onCurrentItemChange = {},
            )
        }
        rule.onNodeWithText("first.jpg").assertIsDisplayed()

        rule.runOnIdle {
            sequenceState.value = ViewerSequence(
                items = listOf(prepended, first, second),
                pageIndexByMediaId = mapOf(prepended.mediaId to 0, first.mediaId to 1, second.mediaId to 2),
            )
        }
        rule.waitForIdle()

        rule.onNodeWithText("first.jpg").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(first, closed) }
    }

    @Test
    fun zoomedPhotoCannotDismissAfterReorderingUntilZoomedOut() {
        val current = mediaItem("zoomed")
        val neighbor = mediaItem("neighbor")
        val sequence = mutableStateOf(sequenceOf(current, neighbor))
        var closed: MediaItem? = null
        rule.setContent {
            MediaDetailScreen(
                initialMediaId = current.mediaId,
                sequence = sequence.value,
                tileBoundsForMediaId = { null },
                onBack = { closed = it },
                onCurrentItemChange = {},
            )
        }
        // Coil completes outside Compose's idling resources. Wait for actual image pixels.
        rule.waitUntil(timeoutMillis = 10_000) {
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
            // JPEG encoding can round the original RGB channels.
            android.graphics.Color.red(pixel) in 56..64 &&
                android.graphics.Color.green(pixel) in 96..104 &&
                android.graphics.Color.blue(pixel) in 156..164
        }
        rule.onRoot().performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeDown() }
        rule.runOnIdle { assertNull(closed) }

        rule.runOnIdle { sequence.value = sequenceOf(neighbor, current) }
        rule.waitForIdle()
        rule.onNodeWithText("zoomed.jpg").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeDown() }
        rule.runOnIdle { assertNull(closed) }
        saveScreenshot("zoom-after-reorder")

        rule.onRoot().performTouchInput { doubleClick() }
        rule.waitForIdle()
        rule.onRoot().performTouchInput { swipeDown() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(current, closed) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 34)
    fun hdrWindowSurvivesReorderingAndResetsOnSdrPage() {
        val current = mediaItem("hdr", hasGainmap = true)
        val neighbor = mediaItem("sdr")
        val sequence = mutableStateOf(sequenceOf(current, neighbor))
        rule.setContent {
            MediaDetailScreen(
                initialMediaId = current.mediaId,
                sequence = sequence.value,
                tileBoundsForMediaId = { null },
                onBack = {},
                onCurrentItemChange = {},
            )
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.activity.window.colorMode == ActivityInfo.COLOR_MODE_HDR
        }
        rule.runOnIdle { sequence.value = sequenceOf(neighbor, current) }
        rule.waitForIdle()
        rule.onNodeWithText("hdr.jpg").assertIsDisplayed()
        rule.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_HDR, rule.activity.window.colorMode) }

        rule.onNodeWithTag(filmstripTileTestTag(0)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_DEFAULT, rule.activity.window.colorMode) }
        rule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(ActivityInfo.COLOR_MODE_HDR, rule.activity.window.colorMode) }
    }

    private fun sequenceOf(vararg items: MediaItem) = ViewerSequence(
        items = items.toList(),
        pageIndexByMediaId = items.mapIndexed { index, item -> item.mediaId to index }.toMap(),
    )

    private fun showViewer(
        items: List<MediaItem>,
        tileBounds: Rect? = Rect(30f, 60f, 150f, 180f),
        onCurrent: (MediaItem) -> Unit = {},
        onClose: (MediaItem) -> Unit,
    ) {
        val sequence = ViewerSequence(
            items = items,
            pageIndexByMediaId = items.mapIndexed { index, item -> item.mediaId to index }.toMap(),
        )
        rule.setContent {
            MediaDetailScreen(
                initialMediaId = items.first().mediaId,
                sequence = sequence,
                tileBoundsForMediaId = { tileBounds },
                onBack = onClose,
                onCurrentItemChange = onCurrent,
            )
        }
        rule.waitForIdle()
    }

    private fun mediaItem(name: String, isVideo: Boolean = false, hasGainmap: Boolean = false): MediaItem {
        val file = File(rule.activity.cacheDir, "$name.jpg")
        val bitmap = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(60, 100, 160))
        val gainmapBitmap = if (hasGainmap) {
            check(Build.VERSION.SDK_INT >= 34)
            Bitmap.createBitmap(200, 150, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.WHITE)
                bitmap.gainmap = Gainmap(this)
            }
        } else {
            null
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        gainmapBitmap?.recycle()
        return MediaItem(
            mediaId = MediaId(name), dayId = 20_000, displayName = "$name.jpg",
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
