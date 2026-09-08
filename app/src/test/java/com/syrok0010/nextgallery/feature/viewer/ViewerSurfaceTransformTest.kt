package com.syrok0010.nextgallery.ui.detail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerSurfaceTransformTest {
    @Test
    fun `opening and closing match tile bounds for portrait and landscape surfaces`() {
        for (surface in listOf(Rect(0f, 200f, 800f, 800f), Rect(200f, 0f, 800f, 1000f))) {
            val opening = requireNotNull(surface.enterTarget(tile))
            val closing = requireNotNull(surface.settleTarget(tile, Offset.Zero, 0f))

            assertRectEquals(tile, screenBounds(surface, resolve(enter = opening, enterProgress = 0f)))
            assertRectEquals(surface, screenBounds(surface, resolve(enter = opening, enterProgress = 1f)))
            assertRectEquals(surface, screenBounds(surface, resolve(settle = closing, settleProgress = 0f)))
            assertRectEquals(tile, screenBounds(surface, resolve(settle = closing, settleProgress = 1f)))
        }
    }

    @Test
    fun `committing predictive back continues from the current offset and scale`() {
        val surface = Rect(0f, 200f, 800f, 800f)
        val predictive = requireNotNull(surface.settleTarget(tile, Offset.Zero, 0f))
        val before = resolve(predictive = predictive, backProgress = 0.4f)
        val closing = requireNotNull(surface.settleTarget(tile, Offset.Zero, 0.4f))
        val after = resolve(settle = closing, settleProgress = 0f, backProgress = 0.4f)

        assertEquals(before.offset, after.offset)
        assertEquals(before.scale, after.scale, 0.0001f)
        assertRectEquals(tile, screenBounds(surface, resolve(settle = closing, settleProgress = 1f)))
    }

    @Test
    fun `closing after drag preserves initial offset and scale`() {
        val surface = Rect(0f, 200f, 800f, 800f)
        val drag = Offset(30f, 180f)
        val before = resolve(drag = drag)
        val closing = requireNotNull(surface.settleTarget(tile, drag, 0f))
        val after = resolve(settle = closing)

        assertEquals(before.offset, after.offset)
        assertEquals(before.scale, after.scale, 0.0001f)
        assertRectEquals(tile, screenBounds(surface, resolve(settle = closing, settleProgress = 1f)))
    }

    @Test
    fun `missing tile or unmeasured surface does not create a transition`() {
        val surface = Rect(0f, 0f, 800f, 600f)
        assertNull(surface.enterTarget(null))
        assertNull(surface.settleTarget(null, Offset.Zero, 0f))
        assertNull(Rect.Zero.enterTarget(tile))
        assertNull(Rect.Zero.settleTarget(tile, Offset.Zero, 0f))
    }

    private fun resolve(
        drag: Offset = Offset.Zero,
        enter: ViewerBoundsTransform? = null,
        enterProgress: Float = 0f,
        settle: ViewerBoundsTransform? = null,
        settleProgress: Float = 0f,
        predictive: ViewerBoundsTransform? = null,
        backProgress: Float = 0f,
    ) = resolveViewerSurfaceTransform(
        dragOffset = drag,
        predictiveBackProgress = backProgress,
        enterPending = false,
        enterTarget = enter,
        enterProgress = enterProgress,
        settleTarget = settle,
        settleProgress = settleProgress,
        predictiveTarget = predictive,
    )

    private fun screenBounds(surface: Rect, transform: ViewerSurfaceTransform): Rect {
        val local = (transform.clipShape?.createOutline(
            Size(surface.width, surface.height), LayoutDirection.Ltr, Density(1f),
        ) as? Outline.Rectangle)?.rect ?: Rect(0f, 0f, surface.width, surface.height)
        val center = surface.center + transform.offset
        return Rect(
            center.x + (local.left - surface.width / 2f) * transform.scale,
            center.y + (local.top - surface.height / 2f) * transform.scale,
            center.x + (local.right - surface.width / 2f) * transform.scale,
            center.y + (local.bottom - surface.height / 2f) * transform.scale,
        )
    }

    private fun assertRectEquals(expected: Rect, actual: Rect) {
        assertEquals(expected.left, actual.left, 0.001f)
        assertEquals(expected.top, actual.top, 0.001f)
        assertEquals(expected.right, actual.right, 0.001f)
        assertEquals(expected.bottom, actual.bottom, 0.001f)
    }

    private val tile = Rect(30f, 60f, 150f, 180f)
}
