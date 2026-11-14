package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.toIntRect
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlin.math.abs

/**
 * A grid item the lazy grid has measured on screen: its [key], its [index] in the list the grid
 * measured, which may be an earlier one, and its [bounds] from the top of the screen.
 */
internal data class MeasuredGridItem(val key: String, val index: Int, val bounds: IntRect)

/** A media tile under the finger. */
internal data class MediaGridTile(val key: String, val mediaId: MediaId)

/**
 * Where the lazy grid scrolls so that it shows [mediaId], or the top of the list when null, with
 * its top [scrollOffsetPx] above the top of the screen.
 */
internal data class MediaScrollRestoration(
    val mediaId: MediaId?,
    val gridIndex: Int,
    val scrollOffsetPx: Int,
)

/** How far down the list the screen is, and the day at its top. */
internal data class MediaScrollPosition(val dayId: Int?, val fraction: Float)

/**
 * The media grid as it is shown: the list of [slots] and what the lazy grid has [measured] of it.
 *
 * Answers about the screen are given in objects and days. Grid indexes come out only as places for
 * the lazy grid to scroll to. For a frame after the list changes, the measurement still describes
 * the earlier list; only keys mean the same in both, so during that frame the answers describe
 * what is still drawn.
 */
internal class MediaGridLayout(
    private val slots: List<MediaSlot>,
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

    /** Whether the lazy grid has measured this list yet, not an earlier one. */
    fun isMeasured(): Boolean = measured().all { items.getOrNull(it.index)?.key == it.key }

    /**
     * Where the lazy grid scrolls to keep the screen as it was in [previous], the list it last
     * measured: the object at the top at the same height, or, when it is gone, the object nearest
     * to it in time. At the very top of the list the screen stays at the top.
     */
    fun restorationFrom(previous: MediaGridLayout): MediaScrollRestoration? {
        val shown = previous.measured()
        val first = previous.items.firstOrNull()?.key
        if (shown.any { it.key == first && it.bounds.top >= 0 }) {
            return MediaScrollRestoration(mediaId = null, gridIndex = 0, scrollOffsetPx = 0)
        }
        val (anchor, top) = shown.firstNotNullOfOrNull { measuredItem ->
            previous.mediaAt(measuredItem.key)?.let { it to measuredItem.bounds.top }
        } ?: return null
        val candidates = items.withIndex().mapNotNull { (index, item) ->
            (item as? MediaGridItem.Slot)?.slot?.mediaItem?.let { index to it }
        }
        val (gridIndex, target) = candidates.firstOrNull { it.second.mediaId == anchor.mediaId }
            ?: candidates.minByOrNull { abs(it.second.canonicalTime - anchor.canonicalTime) }
            ?: return null
        return MediaScrollRestoration(target.mediaId, gridIndex, scrollOffsetPx = -top)
    }

    private fun mediaAt(key: String): MediaItem? =
        (gridIndexes[key]?.let(items::get) as? MediaGridItem.Slot)?.slot?.mediaItem

    private fun measuredSlots(): List<MediaGridItem.Slot> =
        measured().mapNotNull { gridIndexes[it.key]?.let(items::get) as? MediaGridItem.Slot }

    private fun slotAt(fraction: Float): Int =
        ((slots.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
}

/** What [this] grid has measured; read inside a snapshot observer, it follows scrolling. */
internal fun LazyGridState.measuredItems(): List<MeasuredGridItem> =
    layoutInfo.visibleItemsInfo.map {
        // The grid has no padding above, so its offsets start at the top of the screen.
        MeasuredGridItem(it.key as String, it.index, it.size.toIntRect().translate(it.offset))
    }

/** When the object was taken; an object without a time counts from the start of its day. */
private val MediaItem.canonicalTime: Long
    get() = takenAtEpochSeconds ?: (dayId.toLong() * SECONDS_PER_DAY)

private const val SECONDS_PER_DAY = 86_400L
