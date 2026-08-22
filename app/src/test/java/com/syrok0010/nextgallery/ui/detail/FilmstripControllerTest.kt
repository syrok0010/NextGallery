package com.syrok0010.nextgallery.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmstripControllerTest {

    private val controller = FilmstripController()

    @Test
    fun `content padding centers the first and last items in viewport`() {
        val layout = controller.calculateLayout(
            itemCount = 10,
            itemWidthPx = 100f,
            itemSpacingPx = 10f,
            viewportWidthPx = 500f,
        )

        // Viewport center = 250px.
        // Item 0 starts at contentPadding (200px), center is 200 + 50 = 250px (viewport center).
        assertEquals(200f, layout.contentPaddingPx, 0.001f)
    }

    @Test
    fun `scroll offset for index centers target item`() {
        val layout = controller.calculateLayout(
            itemCount = 5,
            itemWidthPx = 100f,
            itemSpacingPx = 20f,
            viewportWidthPx = 400f,
        )

        // Step = 120px. Content padding = (400 - 100) / 2 = 150px.
        // For index 0, scroll offset is 0. Item 0 center in viewport: 150 + 0*120 + 50 - 0 = 200 (center).
        assertEquals(0f, layout.scrollOffsetForIndex(0), 0.001f)
        assertEquals(120f, layout.scrollOffsetForIndex(1), 0.001f)
        assertEquals(240f, layout.scrollOffsetForIndex(2), 0.001f)
        assertEquals(360f, layout.scrollOffsetForIndex(3), 0.001f)
        assertEquals(480f, layout.scrollOffsetForIndex(4), 0.001f)

        // Item offset from center at its own scroll offset should be 0
        for (i in 0 until 5) {
            val offset = layout.scrollOffsetForIndex(i)
            assertEquals(0f, layout.itemOffsetFromCenter(i, offset), 0.001f)
        }
    }

    @Test
    fun `index for scroll offset rounds to nearest item`() {
        val layout = controller.calculateLayout(
            itemCount = 5,
            itemWidthPx = 100f,
            itemSpacingPx = 20f,
            viewportWidthPx = 400f,
        )

        // Step = 120px.
        assertEquals(0, layout.indexForScrollOffset(0f))
        assertEquals(0, layout.indexForScrollOffset(50f))
        assertEquals(1, layout.indexForScrollOffset(60f))
        assertEquals(1, layout.indexForScrollOffset(120f))
        assertEquals(1, layout.indexForScrollOffset(179f))
        assertEquals(2, layout.indexForScrollOffset(180f))
        assertEquals(4, layout.indexForScrollOffset(1000f)) // clamped to max index
    }

    @Test
    fun `snap scroll offset aligns to the nearest item center`() {
        val layout = controller.calculateLayout(
            itemCount = 5,
            itemWidthPx = 100f,
            itemSpacingPx = 20f,
            viewportWidthPx = 400f,
        )

        assertEquals(0f, layout.snapScrollOffset(40f), 0.001f)
        assertEquals(120f, layout.snapScrollOffset(70f), 0.001f)
        assertEquals(240f, layout.snapScrollOffset(220f), 0.001f)
    }

    @Test
    fun `index for tap resolves correctly according to tap coordinate in viewport`() {
        val layout = controller.calculateLayout(
            itemCount = 5,
            itemWidthPx = 100f,
            itemSpacingPx = 20f,
            viewportWidthPx = 500f,
        )

        // Content padding = (500 - 100) / 2 = 200px. Step = 120px.
        // When scrollOffset = 0 (item 0 centered at 250px):
        // Item 0 bounds in viewport: [200, 300] -> tap at 250 is index 0.
        assertEquals(0, layout.indexForTap(tapPositionX = 250f, currentScrollOffsetPx = 0f))
        // Item 1 bounds in viewport: [320, 420] -> tap at 350 is index 1.
        assertEquals(1, layout.indexForTap(tapPositionX = 350f, currentScrollOffsetPx = 0f))

        // When scrollOffset = 120 (item 1 centered at 250px):
        // Item 0 bounds in viewport: [80, 180] -> tap at 100 is index 0.
        // Item 1 bounds in viewport: [200, 300] -> tap at 250 is index 1.
        // Item 2 bounds in viewport: [320, 420] -> tap at 350 is index 2.
        assertEquals(0, layout.indexForTap(tapPositionX = 100f, currentScrollOffsetPx = 120f))
        assertEquals(1, layout.indexForTap(tapPositionX = 250f, currentScrollOffsetPx = 120f))
        assertEquals(2, layout.indexForTap(tapPositionX = 350f, currentScrollOffsetPx = 120f))
    }

    @Test
    fun `drag delta scrolls and clamps within sequence bounds`() {
        val layout = controller.calculateLayout(
            itemCount = 3,
            itemWidthPx = 100f,
            itemSpacingPx = 20f,
            viewportWidthPx = 400f,
        )

        // Max scroll offset = (3 - 1) * 120 = 240px.
        // Dragging left (negative deltaX) increases scroll offset
        assertEquals(50f, layout.scrollOffsetAfterDrag(initialScrollOffsetPx = 0f, dragDeltaX = -50f), 0.001f)
        // Dragging right beyond start clamps to 0
        assertEquals(0f, layout.scrollOffsetAfterDrag(initialScrollOffsetPx = 0f, dragDeltaX = 50f), 0.001f)
        // Dragging left beyond end clamps to maxScrollOffset (240)
        assertEquals(240f, layout.scrollOffsetAfterDrag(initialScrollOffsetPx = 200f, dragDeltaX = -100f), 0.001f)
    }

    @Test
    fun `edge cases with empty and single item list`() {
        val emptyLayout = controller.calculateLayout(
            itemCount = 0,
            itemWidthPx = 100f,
            itemSpacingPx = 10f,
            viewportWidthPx = 400f,
        )
        assertEquals(0f, emptyLayout.scrollOffsetForIndex(0), 0.001f)
        assertEquals(0, emptyLayout.indexForScrollOffset(100f))
        assertEquals(0, emptyLayout.indexForTap(100f, 0f))
        assertEquals(0f, emptyLayout.maxScrollOffsetPx, 0.001f)

        val singleLayout = controller.calculateLayout(
            itemCount = 1,
            itemWidthPx = 100f,
            itemSpacingPx = 10f,
            viewportWidthPx = 400f,
        )
        assertEquals(0f, singleLayout.scrollOffsetForIndex(0), 0.001f)
        assertEquals(0, singleLayout.indexForScrollOffset(50f))
        assertEquals(0, singleLayout.indexForTap(200f, 0f))
        assertEquals(0f, singleLayout.maxScrollOffsetPx, 0.001f)
        assertEquals(0f, singleLayout.itemOffsetFromCenter(0, 0f), 0.001f)
    }
}
