package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.syrok0010.nextgallery.core.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaGridLayoutTest {
    private val day1 = listOf(slot("a", 1, 0), slot("b", 1, 1), slot("c", 1, 2))
    private val day2 = listOf(slot("d", 2, 0), slot("e", 2, 1))

    /** What the grid has measured; the tests set it as the lazy grid would. */
    private var measured = emptyList<MeasuredGridItem>()

    // Two columns of 100 px. Header 1, then a b / c _, then header 2, then d.
    private val screen = listOf(
        MeasuredGridItem(headerKey(1), 0, IntRect(0, 0, 200, 40)),
        MeasuredGridItem(gridKey("a"), 1, IntRect(0, 40, 100, 140)),
        MeasuredGridItem(gridKey("b"), 2, IntRect(100, 40, 200, 140)),
        MeasuredGridItem(gridKey("c"), 3, IntRect(0, 140, 100, 240)),
        MeasuredGridItem(headerKey(2), 4, IntRect(0, 240, 200, 280)),
        MeasuredGridItem(gridKey("d"), 5, IntRect(0, 280, 100, 380)),
    )

    private fun layout(slots: List<MediaSlot>) = MediaGridLayout(slots) { measured }

    @Test fun `a press starts only on a media tile`() {
        measured = screen
        val grid = layout(day1 + day2)

        assertEquals(gridKey("b"), grid.tileAt(IntOffset(150, 90), exact = true)?.key)
        assertEquals(MediaId("b"), grid.tileAt(IntOffset(150, 90), exact = true)?.mediaId)
        assertNull(grid.tileAt(IntOffset(150, 190), exact = true))
        assertNull(grid.tileAt(IntOffset(50, 20), exact = true))
    }

    @Test fun `off a tile the last one before the finger stands in`() {
        measured = screen
        val grid = layout(day1 + day2)

        assertEquals(gridKey("c"), grid.tileAt(IntOffset(150, 190), exact = false)?.key)
        assertEquals(gridKey("c"), grid.tileAt(IntOffset(50, 260), exact = false)?.key)
        assertEquals(gridKey("d"), grid.tileAt(IntOffset(150, 500), exact = false)?.key)
    }

    @Test fun `a measurement of the earlier list still names what is drawn`() {
        // Day 2 is on screen; day 1 has just been loaded above it, and the grid has not
        // measured the new list yet.
        measured = listOf(
            MeasuredGridItem(headerKey(2), 0, IntRect(0, 0, 200, 40)),
            MeasuredGridItem(gridKey("d"), 1, IntRect(0, 40, 100, 140)),
            MeasuredGridItem(gridKey("e"), 2, IntRect(100, 40, 200, 140)),
        )
        val grid = layout(day1 + day2)

        assertEquals(3..4, grid.visibleSlots())
        assertEquals(MediaScrollPosition(dayId = 2, fraction = 3f / 4), grid.scrollPosition())
        assertEquals(MediaId("e"), grid.tileAt(IntOffset(150, 90), exact = true)?.mediaId)
    }

    @Test fun `a measured placeholder that has been loaded since is left out`() {
        measured = listOf(
            MeasuredGridItem("slot:1:0", 1, IntRect(0, 0, 100, 100)),
            MeasuredGridItem(gridKey("b"), 2, IntRect(100, 0, 200, 100)),
        )
        val grid = layout(day1)

        assertEquals(1..1, grid.visibleSlots())
        assertNull(grid.tileAt(IntOffset(50, 50), exact = true))
    }

    @Test fun `nothing measured from the list gives no visible slots`() {
        measured = listOf(MeasuredGridItem(gridKey("gone"), 1, IntRect(0, 0, 100, 100)))
        val grid = layout(day1)

        assertNull(grid.visibleSlots())
        assertEquals(MediaScrollPosition(dayId = null, fraction = 0f), grid.scrollPosition())
    }

    @Test fun `positions in the list map to grid indexes past the day headers`() {
        val grid = layout(listOf(placeholder(1, 0)) + day1.drop(1) + day2)

        // Grid: header 1, slot, b, c, header 2, d, e.
        assertEquals(1, grid.gridIndexAt(0f))
        assertEquals(6, grid.gridIndexAt(1f))
        assertEquals(5, grid.gridIndexOf(MediaId("d")))
        assertNull(grid.gridIndexOf(MediaId("gone")))
        assertEquals(1, grid.dayAt(0.4f))
        assertEquals(2, grid.dayAt(1f))
        assertNull(layout(emptyList()).gridIndexAt(0.5f))
    }
}
