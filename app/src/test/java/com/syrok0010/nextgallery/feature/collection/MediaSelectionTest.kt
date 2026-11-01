package com.syrok0010.nextgallery.feature.collection

import androidx.compose.runtime.saveable.SaverScope
import com.syrok0010.nextgallery.core.media.MediaId
import org.junit.Assert.*
import org.junit.Test

class MediaSelectionTest {
    private val first = MediaId("first")
    private val second = MediaId("second")

    @Test fun `long press enters the mode and never deselects`() {
        val selection = MediaSelection()
        selection.select(first)
        selection.select(first)
        assertTrue(selection.active)
        assertEquals(setOf(first), selection.selectedIds)
    }

    @Test fun `mode stays active after the last item is deselected`() {
        val selection = MediaSelection()
        selection.select(first)
        selection.toggle(second)
        selection.toggle(first)
        selection.toggle(second)
        assertTrue(selection.active)
        assertTrue(selection.selectedIds.isEmpty())
    }

    @Test fun `departed album members leave selection without dropping other members`() {
        val selection = MediaSelection()
        selection.select(first)
        selection.select(second)
        selection.remove(setOf(first))
        assertTrue(selection.active)
        assertEquals(setOf(second), selection.selectedIds)
    }

    @Test fun `clear leaves the mode`() {
        val selection = MediaSelection()
        selection.select(first)
        selection.clear()
        assertFalse(selection.active)
        assertFalse(selection.isSelected(first))
    }

    @Test fun `saver restores mode and selected ids`() {
        val selection = MediaSelection()
        selection.select(first)
        selection.toggle(second)
        val saved = with(MediaSelection.Saver) { SaverScope { true }.save(selection) }!!
        val restored = MediaSelection.Saver.restore(saved)!!
        assertTrue(restored.active)
        assertEquals(setOf(first, second), restored.selectedIds)
    }
}
