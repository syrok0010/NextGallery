package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DragSelectionTest {
    // Day 1: a, b, c; day 2: d, e. A header opens each day.
    private val slots = listOf(
        slot("a", 1, 0),
        slot("b", 1, 1),
        slot("c", 1, 2),
        slot("d", 2, 0),
        slot("e", 2, 1),
    )
    private val items = slots.toMediaGridItems()

    private fun ids(vararg names: String) = names.mapTo(HashSet(), ::MediaId)

    private fun key(name: String) =
        items
            .first {
                (it as? MediaGridItem.Slot)?.slot?.mediaItem?.mediaId?.value == name
            }.key

    @Test fun `range runs in reading order across days, both ways`() {
        val range = RangeSelection(emptySet(), key("b"), adding = true)
        assertEquals(ids("b", "c", "d"), range.selectedIds(items, key("d")))
        assertEquals(ids("a", "b"), range.selectedIds(items, key("a")))
    }

    @Test fun `dragging back gives the tiles their state from before the gesture`() {
        val range = RangeSelection(ids("e"), key("a"), adding = true)
        assertEquals(ids("a", "b", "c", "d", "e"), range.selectedIds(items, key("e")))
        assertEquals(ids("a", "e"), range.selectedIds(items, key("a")))
    }

    @Test fun `a gesture started on a selected tile deselects the range only`() {
        val range = RangeSelection(ids("a", "b", "c", "e"), key("b"), adding = false)
        assertEquals(ids("a", "e"), range.selectedIds(items, key("d")))
    }

    @Test fun `an end gone from the list leaves the selection alone`() {
        val range = RangeSelection(emptySet(), key("b"), adding = true)
        assertNull(range.selectedIds(items.filterNot { it.key == key("b") }, key("c")))
    }

    // Two columns of 100 px; row 0: a, b; row 1: c, empty; a header 100..140; row 2: d.
    private val cells = listOf(
        GridCell(1, "a", IntRect(0, 0, 100, 100)),
        GridCell(2, "b", IntRect(100, 0, 200, 100)),
        GridCell(3, "c", IntRect(0, 100, 100, 200)),
        GridCell(5, "d", IntRect(0, 240, 100, 340)),
    )

    @Test fun `a press starts only on a tile`() {
        assertEquals("b", cellAt(cells, IntOffset(150, 50), exact = true)?.key)
        assertNull(cellAt(cells, IntOffset(150, 150), exact = true))
    }

    @Test fun `off a tile the last one before the finger stands in`() {
        assertEquals("c", cellAt(cells, IntOffset(150, 150), exact = false)?.key)
        assertEquals("c", cellAt(cells, IntOffset(50, 220), exact = false)?.key)
        assertEquals("d", cellAt(cells, IntOffset(150, 400), exact = false)?.key)
    }

    @Test fun `a layout measured before an update still names the objects it shows`() {
        // Day 1 was loaded above while the layout still shows day 2 at the top of the list:
        // by index it would point at the new header and tiles a and b.
        val earlier = listOf(slot("d", 2, 0), slot("e", 2, 1)).toMediaGridItems()
        val measured = earlier.map { it.key }
        val grid = MediaGridSnapshot(slots)

        assertEquals(
            listOf("d" to 3, "e" to 4),
            grid.measuredSlots(measured).map { it.slot.mediaItem?.mediaId?.value to it.slotIndex },
        )
    }

    @Test fun `a measured placeholder that has been loaded since is left out`() {
        val placeholder = MediaGridItem.Slot(0, MediaSlot(MediaSlotKey(1, 0), 1, 0, null))
        val grid = MediaGridSnapshot(slots)

        assertNull(grid[placeholder.key])
        assertEquals(
            listOf(key("a")),
            grid.measuredSlots(listOf(placeholder.key, key("a"))).map { it.key },
        )
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

    private fun slot(id: String, dayId: Int, indexInDay: Int) =
        MediaSlot(MediaSlotKey(dayId, indexInDay), dayId, indexInDay, media(id, dayId))

    private fun media(id: String, dayId: Int) =
        MediaItem(
            mediaId = MediaId(id),
            dayId = dayId,
            displayName = "$id.jpg",
            mimeType = "image/jpeg",
            width = 1_024,
            height = 768,
            etag = null,
            livePhotoId = null,
            auid = "auid-$id",
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = dayId * 86_400L,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.MemoriesFile(id.hashCode().toLong()),
        )
}
