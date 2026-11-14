package com.syrok0010.nextgallery.feature.collection

import androidx.compose.ui.unit.IntRect
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** After the list changes, the screen keeps showing the objects it showed. */
class MediaScrollRestorationTest {
    private val anchor = slot("anchor", 20_000, 0)
    private val newer = slot("newer", 20_001, 0)

    @Test fun `adding media during the first load keeps the screen at the top`() {
        val local = slot("local", 20_000, 0)
        val before =
            layout(
                local,
                measured = listOf(
                    at(headerKey(20_000), 0, top = 0),
                    at(gridKey("local"), 1, top = 32),
                ),
            )

        assertEquals(
            MediaScrollRestoration(mediaId = null, gridIndex = 0, scrollOffsetPx = 0),
            layout(newer, local).restorationFrom(before),
        )
    }

    @Test fun `adding media above the screen keeps the object at the same height`() {
        val before = layout(anchor, measured = listOf(at(gridKey("anchor"), 1, top = -24)))

        // Grid: header, newer, header, anchor.
        assertEquals(
            MediaScrollRestoration(anchor.mediaId(), gridIndex = 3, scrollOffsetPx = 24),
            layout(newer, anchor).restorationFrom(before),
        )
    }

    @Test fun `removing media above the screen keeps the object at the same height`() {
        val before = layout(newer, anchor, measured = listOf(at(gridKey("anchor"), 3, top = 18)))

        assertEquals(
            MediaScrollRestoration(anchor.mediaId(), gridIndex = 1, scrollOffsetPx = -18),
            layout(anchor).restorationFrom(before),
        )
    }

    @Test fun `a merged copy of the object is the same object`() {
        val local = anchor.withAsset(
            MediaAssetRef.LocalContent("content://media/external/images/media/42", 1, 1),
        )
        val merged = anchor.withAsset(
            MediaAssetRef.LocalFirst(
                local = local.mediaItem!!.assetRef as MediaAssetRef.LocalContent,
                remote = MediaAssetRef.MemoriesFile(42),
            ),
        )
        val before = layout(local, measured = listOf(at(gridKey("anchor"), 1, top = -12)))

        assertEquals(
            MediaScrollRestoration(anchor.mediaId(), gridIndex = 1, scrollOffsetPx = 12),
            layout(merged).restorationFrom(before),
        )
    }

    @Test fun `a removed object gives way to the one nearest to it in time`() {
        val removed = slot("removed", 20_000, 0).takenAt(1_728_000_000)
        val farther = slot("farther", 20_000, 0).takenAt(1_727_999_000)
        val nearest = slot("nearest", 20_000, 1).takenAt(1_728_000_010)
        val before = layout(removed, measured = listOf(at(gridKey("removed"), 1, top = -8)))

        assertEquals(
            MediaScrollRestoration(MediaId("nearest"), gridIndex = 2, scrollOffsetPx = 8),
            layout(farther, nearest).restorationFrom(before),
        )
    }

    @Test fun `the object is found by key when the measured index is from an earlier list`() {
        val before = layout(newer, anchor, measured = listOf(at(gridKey("anchor"), 1, top = -6)))

        assertEquals(
            MediaScrollRestoration(anchor.mediaId(), gridIndex = 1, scrollOffsetPx = 6),
            layout(anchor).restorationFrom(before),
        )
    }

    @Test fun `a list is measured once the grid has laid it out`() {
        val earlier = listOf(at(gridKey("anchor"), 1, top = 0))
        val current = listOf(at(gridKey("anchor"), 3, top = 0))

        assertFalse(layout(newer, anchor, measured = earlier).isMeasured())
        assertTrue(layout(newer, anchor, measured = current).isMeasured())
        assertTrue(layout(newer, anchor).isMeasured())
    }

    private fun layout(vararg slots: MediaSlot, measured: List<MeasuredGridItem> = emptyList()) =
        MediaGridLayout(slots.toList()) { measured }

    private fun at(key: String, index: Int, top: Int) =
        MeasuredGridItem(key, index, IntRect(0, top, 100, top + 100))

    private fun MediaSlot.mediaId() = mediaItem!!.mediaId

    private fun MediaSlot.takenAt(seconds: Long) =
        copy(mediaItem = mediaItem!!.copy(takenAtEpochSeconds = seconds))

    private fun MediaSlot.withAsset(asset: MediaAssetRef) =
        copy(mediaItem = mediaItem!!.copy(assetRef = asset))
}
