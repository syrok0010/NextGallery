package com.syrok0010.nextgallery.feature.collection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragSelectionTest {
    // Day 1: a, b, c; day 2: d, e. A header opens each day.
    private val layout = MediaGridLayout(
        listOf(slot("a", 1, 0), slot("b", 1, 1), slot("c", 1, 2), slot("d", 2, 0), slot("e", 2, 1)),
    ) { emptyList() }

    @Test fun `range runs in reading order across days, both ways`() {
        val range = RangeSelection(emptySet(), gridKey("b"), adding = true)
        assertEquals(ids("b", "c", "d"), range.selectedIds(layout, gridKey("d")))
        assertEquals(ids("a", "b"), range.selectedIds(layout, gridKey("a")))
    }

    @Test fun `dragging back gives the tiles their state from before the gesture`() {
        val range = RangeSelection(ids("e"), gridKey("a"), adding = true)
        assertEquals(ids("a", "b", "c", "d", "e"), range.selectedIds(layout, gridKey("e")))
        assertEquals(ids("a", "e"), range.selectedIds(layout, gridKey("a")))
    }

    @Test fun `a gesture started on a selected tile deselects the range only`() {
        val range = RangeSelection(ids("a", "b", "c", "e"), gridKey("b"), adding = false)
        assertEquals(ids("a", "e"), range.selectedIds(layout, gridKey("d")))
    }

    @Test fun `an end gone from the list leaves the selection alone`() {
        val range = RangeSelection(emptySet(), gridKey("b"), adding = true)
        val withoutB = MediaGridLayout(listOf(slot("a", 1, 0), slot("c", 1, 2))) { emptyList() }
        assertNull(range.selectedIds(withoutB, gridKey("c")))
    }

    @Test fun `scroll speed grows towards the edges and is full beyond them`() {
        assertEquals(0f, edgeScrollSpeed(500f, 1000f, 100f, 50f), 0f)
        assertEquals(-25f, edgeScrollSpeed(50f, 1000f, 100f, 50f), 0.001f)
        assertEquals(25f, edgeScrollSpeed(950f, 1000f, 100f, 50f), 0.001f)
        assertEquals(-50f, edgeScrollSpeed(-30f, 1000f, 100f, 50f), 0f)
        assertEquals(50f, edgeScrollSpeed(1200f, 1000f, 100f, 50f), 0f)
    }

    @Test fun `a drag enters the mode with exactly its selection`() {
        val selection = MediaSelection()
        selection.replace(ids("a", "b"))
        assertTrue(selection.active)
        assertEquals(ids("a", "b"), selection.selectedIds)
    }
}
