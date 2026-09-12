package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerMotionStateTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var motion: ViewerMotionState
    private val firstId = MediaId("first")
    private val currentId = mutableStateOf(firstId)
    private val closedIds = mutableListOf<MediaId>()
    private val surface = Rect(0f, 100f, 800f, 700f)
    private val tile = Rect(20f, 40f, 140f, 160f)

    @Test
    fun openingWaitsForMeasurementThenRestoresSurfaceAndChrome() {
        showViewer(measure = false)
        rule.runOnIdle {
            assertEquals(0f, motion.surfaceTransform(firstId).alpha, 0f)
            assertFalse(motion.chromeAllowed)
            motion.onSurfaceBoundsChange(surface)
        }
        finishAnimations()
        rule.runOnIdle { assertResting() }
    }

    @Test
    fun shortDragReturnsToRestWithoutClosing() {
        showViewer()
        rule.runOnIdle { assertTrue(motion.dragBy(Offset(10f, 60f))) }
        rule.runOnIdle {
            assertFalse(motion.chromeAllowed)
            motion.endDrag(canClose = true, thresholdPx = 112f)
        }
        finishAnimations()
        rule.runOnIdle {
            assertResting()
            assertTrue(closedIds.isEmpty())
        }
    }

    @Test
    fun cancelledDragReturnsToRestWithoutClosing() {
        showViewer()
        rule.runOnIdle { motion.dragBy(Offset(20f, 180f)) }
        rule.runOnIdle { motion.cancelDrag() }
        finishAnimations()
        rule.runOnIdle {
            assertResting()
            assertTrue(closedIds.isEmpty())
        }
    }

    @Test
    fun longDragClosesAfterSettlingAndRetainsItemCapturedAtRelease() {
        showViewer()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { motion.dragBy(Offset(20f, 180f)) }
        rule.runOnIdle { motion.endDrag(canClose = true, thresholdPx = 112f) }
        rule.runOnIdle {
            assertTrue(closedIds.isEmpty())
            currentId.value = MediaId("second")
        }
        finishAnimations()
        rule.runOnIdle {
            assertEquals(listOf(firstId), closedIds)
            assertEquals(tile.center - surface.center, motion.surfaceTransform(firstId).offset)
            assertEquals(0f, motion.backgroundAlpha, 0f)
        }
    }

    @Test
    fun cancellingPredictiveBackRestoresChromeAndSurface() {
        showViewer()
        startBack()
        rule.runOnIdle {
            assertFalse(motion.chromeAllowed)
            assertTrue(motion.surfaceTransform(firstId).scale < 1f)
            rule.activity.onBackPressedDispatcher.dispatchOnBackCancelled()
        }
        finishAnimations()
        rule.runOnIdle {
            assertResting()
            assertTrue(closedIds.isEmpty())
        }
    }

    @Test
    fun committingPredictiveBackSettlesBeforeClosing() {
        showViewer()
        startBack()
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.runOnIdle { assertTrue(closedIds.isEmpty()) }
        finishAnimations()
        rule.runOnIdle {
            assertEquals(listOf(firstId), closedIds)
            assertEquals(tile.center - surface.center, motion.surfaceTransform(firstId).offset)
        }
    }

    @Test
    fun missingTileStillOpensAndCloses() {
        showViewer(tileBounds = null)
        rule.runOnIdle {
            assertResting()
            motion.close()
        }
        finishAnimations()
        rule.runOnIdle {
            assertEquals(listOf(firstId), closedIds)
            assertEquals(0f, motion.backgroundAlpha, 0f)
        }
    }

    private fun showViewer(tileBounds: Rect? = tile, measure: Boolean = true) {
        rule.setContent {
            val id = currentId.value
            motion = rememberViewerMotionState(
                initialMediaId = firstId,
                currentMediaId = id,
                tileBoundsForMediaId = { tileBounds },
                onClose = { closedIds += id },
            )
            Box(
                Modifier.size(200.dp)
                    .background(Color.Black.copy(alpha = motion.backgroundAlpha))
                    .viewerSurfaceTransform(motion.surfaceTransform(id)),
            )
        }
        if (measure) rule.runOnIdle { motion.onSurfaceBoundsChange(surface) }
        finishAnimations()
    }

    private fun startBack() {
        rule.runOnIdle {
            rule.activity.onBackPressedDispatcher.dispatchOnBackStarted(backEvent(0f))
            rule.activity.onBackPressedDispatcher.dispatchOnBackProgressed(backEvent(0.4f))
        }
        rule.waitForIdle()
    }

    private fun backEvent(progress: Float) = BackEventCompat(0f, 300f, progress, BackEventCompat.EDGE_LEFT)

    private fun finishAnimations() {
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
    }

    private fun assertResting() {
        val transform = motion.surfaceTransform(currentId.value)
        assertEquals(Offset.Zero, transform.offset)
        assertEquals(1f, transform.scale, 0f)
        assertEquals(1f, transform.alpha, 0f)
        assertEquals(1f, motion.backgroundAlpha, 0f)
        assertTrue(motion.chromeAllowed)
        assertTrue(motion.trackSurfaceBounds)
    }
}
