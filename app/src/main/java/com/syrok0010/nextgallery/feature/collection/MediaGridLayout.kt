package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.toIntRect
import com.syrok0010.nextgallery.core.media.MediaId

/** A grid item the lazy grid has measured on screen. */
internal data class MeasuredGridItem(val key: String, val bounds: IntRect)

/** A media tile under the finger. */
internal data class MediaGridTile(val key: String, val mediaId: MediaId)

/** How far down the list the screen is, and the day at its top. */
internal data class MediaScrollPosition(val dayId: Int?, val fraction: Float)

/**
 * The media grid as it is shown: the list of [slots] and what the lazy grid has [measured] of it.
 *
 * Every answer about the screen is given in objects and days, never in grid positions. For a frame
 * after the list changes, the measurement still describes the earlier list; only keys mean the
 * same in both, so during that frame the answers describe what is still drawn.
 */
internal class MediaGridLayout(
    val slots: List<MediaSlot>,
    private val measured: () -> List<MeasuredGridItem>,
) {
    /** What the lazy grid draws, in order: a header before every day, then its slots. */
    val items: List<MediaGridItem> = slots.toMediaGridItems()
    private val gridIndexes: Map<String, Int> =
        items.withIndex().associate { (index, item) -> item.key to index }
    private val slotGridIndexes: IntArray =
        items.indices.filter { items[it] is MediaGridItem.Slot }.toIntArray()

    /** Slots from the first visible to the last; null while nothing measured is in the list. */
    fun visibleSlots(): IntRange? {
        val indexes = measuredSlots().map { it.slotIndex }
        val first = indexes.minOrNull() ?: return null
        return first..indexes.max()
    }

    fun scrollPosition(): MediaScrollPosition {
        val top = measuredSlots().minByOrNull { it.slotIndex }
        val fraction = if (top == null || slots.size <= 1) {
            0f
        } else {
            top.slotIndex.toFloat() / (slots.size - 1)
        }
        return MediaScrollPosition(top?.slot?.dayId, fraction.coerceIn(0f, 1f))
    }

    /** The day [fraction] of the way down the list. */
    fun dayAt(fraction: Float): Int? = slots.getOrNull(slotAt(fraction))?.dayId

    /** Where the lazy grid scrolls to show the slot [fraction] of the way down. */
    fun gridIndexAt(fraction: Float): Int? = slotGridIndexes.getOrNull(slotAt(fraction))

    /** Where the lazy grid scrolls to show [mediaId]; null when the list does not hold it. */
    fun gridIndexOf(mediaId: MediaId): Int? =
        items
            .indexOfFirst { (it as? MediaGridItem.Slot)?.slot?.mediaItem?.mediaId == mediaId }
            .takeIf { it >= 0 }

    /**
     * The media tile under [point]. Unless [exact], over a header, a gap or the empty end of a row
     * the last tile before the point in reading order stands in, so a drag follows the finger
     * without jumps.
     */
    fun tileAt(point: IntOffset, exact: Boolean): MediaGridTile? {
        val tiles = measured().mapNotNull { measuredItem ->
            val item = gridIndexes[measuredItem.key]?.let(items::get) as? MediaGridItem.Slot
            val mediaId = item?.slot?.mediaItem?.mediaId ?: return@mapNotNull null
            measuredItem.bounds to MediaGridTile(measuredItem.key, mediaId)
        }
        tiles.firstOrNull { (bounds, _) -> bounds.contains(point) }?.let { return it.second }
        if (exact) return null
        val before = tiles.lastOrNull { (bounds, _) ->
            bounds.top <= point.y && (bounds.bottom <= point.y || bounds.left <= point.x)
        }
        return (before ?: tiles.firstOrNull())?.second
    }

    /** Objects from tile [fromKey] to tile [toKey] in reading order; null when one is gone. */
    fun mediaIdsBetween(fromKey: String, toKey: String): Set<MediaId>? {
        val from = gridIndexes[fromKey] ?: return null
        val to = gridIndexes[toKey] ?: return null
        return items
            .subList(minOf(from, to), maxOf(from, to) + 1)
            .mapNotNullTo(HashSet()) { (it as? MediaGridItem.Slot)?.slot?.mediaItem?.mediaId }
    }

    private fun measuredSlots(): List<MediaGridItem.Slot> =
        measured().mapNotNull { gridIndexes[it.key]?.let(items::get) as? MediaGridItem.Slot }

    private fun slotAt(fraction: Float): Int =
        ((slots.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
}

/** What [this] grid has measured; read inside a snapshot observer, it follows scrolling. */
internal fun LazyGridState.measuredItems(): List<MeasuredGridItem> =
    layoutInfo.visibleItemsInfo.map {
        MeasuredGridItem(it.key as String, it.size.toIntRect().translate(it.offset))
    }
