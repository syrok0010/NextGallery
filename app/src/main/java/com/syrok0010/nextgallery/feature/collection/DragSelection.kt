package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import com.syrok0010.nextgallery.core.media.MediaId
import kotlinx.coroutines.flow.collectLatest

/**
 * Long press on a tile, then drag: every tile between the first one and the finger, in reading
 * order across days, gets the state opposite to the first tile's. Tiles outside the range keep
 * the state they had before the gesture, so dragging back undoes what the range covered.
 */
internal class RangeSelection(
    private val before: Set<MediaId>,
    private val anchorKey: String,
    /** False when the gesture started on a selected tile: the range is deselected. */
    private val adding: Boolean,
) {
    /** Null when the list no longer holds one of the ends: the selection stays as it is. */
    fun selectedIds(items: List<MediaGridItem>, currentKey: String): Set<MediaId>? {
        val from = items.indexOfFirst { it.key == anchorKey }
        val to = items.indexOfFirst { it.key == currentKey }
        if (from < 0 || to < 0) return null
        val range = items
            .subList(minOf(from, to), maxOf(from, to) + 1)
            .mapNotNullTo(HashSet()) { (it as? MediaGridItem.Slot)?.slot?.mediaItem?.mediaId }
        return if (adding) before + range else before - range
    }
}

/** A tile on screen that a drag can select. */
internal data class GridCell(val index: Int, val key: String, val bounds: IntRect)

/**
 * The tile under [point]. Over a header, a gap or the empty end of a row, the last tile before
 * the point in reading order stands in, so the range follows the finger without jumps.
 */
internal fun cellAt(cells: List<GridCell>, point: IntOffset, exact: Boolean): GridCell? {
    cells.firstOrNull { it.bounds.contains(point) }?.let { return it }
    if (exact) return null
    val before = cells.filter {
        it.bounds.top <= point.y && (it.bounds.bottom <= point.y || it.bounds.left <= point.x)
    }
    return before.maxByOrNull { it.index } ?: cells.minByOrNull { it.index }
}

/**
 * Pixels per second to scroll while the finger is [y] in a list [height] high: faster the deeper
 * it is in the [edge] zone at the top (negative) or the bottom, full speed beyond the list.
 */
internal fun edgeScrollSpeed(y: Float, height: Float, edge: Float, maxSpeed: Float): Float =
    when {
        y < edge -> -maxSpeed * ((edge - y) / edge).coerceAtMost(1f)
        y > height - edge -> maxSpeed * ((y - (height - edge)) / edge).coerceAtMost(1f)
        else -> 0f
    }

/** One drag across the grid: [start] on a long press, [moveTo] with the finger, [end] on lift. */
internal class GridDragSelection(
    private val selection: MediaSelection,
    private val gridState: LazyGridState,
    private val haptics: HapticFeedback,
    private val edge: Float,
    private val maxSpeed: Float,
) {
    var items: List<MediaGridItem> = emptyList()
    var speed by mutableFloatStateOf(0f)
        private set
    private var range: RangeSelection? = null
    private var pointer = Offset.Zero

    /** False when the press is not on a tile: the gesture is not a selection. */
    fun start(at: Offset): Boolean {
        val cell = cellAt(cells(), at.round(), exact = true) ?: return false
        val id = (items.getOrNull(cell.index) as? MediaGridItem.Slot)?.slot?.mediaItem?.mediaId
            ?: return false
        val adding = !(selection.active && selection.isSelected(id))
        range = RangeSelection(selection.selectedIds, cell.key, adding)
        pointer = at
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        follow()
        return true
    }

    fun moveTo(at: Offset, height: Float) {
        pointer = at
        speed = edgeScrollSpeed(at.y, height, edge, maxSpeed)
        follow()
    }

    /** Also called while the grid scrolls under a finger that stands still. */
    fun follow() {
        val range = range ?: return
        val cell = cellAt(cells(), pointer.round(), exact = false) ?: return
        range.selectedIds(items, cell.key)?.let(selection::replace)
    }

    fun end() {
        range = null
        speed = 0f
    }

    suspend fun scrollBy(pixels: Float) {
        gridState.scrollBy(pixels)
    }

    private fun cells(): List<GridCell> =
        gridState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
            val slot = items.getOrNull(info.index) as? MediaGridItem.Slot
            if (slot?.slot?.mediaItem == null) return@mapNotNull null
            GridCell(info.index, info.key as String, info.size.toIntRect().translate(info.offset))
        }
}

@Composable
internal fun rememberGridDragSelection(
    selection: MediaSelection,
    gridState: LazyGridState,
    items: List<MediaGridItem>,
): GridDragSelection {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val drag = remember(selection, gridState, haptics, density) {
        with(density) {
            GridDragSelection(
                selection,
                gridState,
                haptics,
                edge = DragScrollEdge.toPx(),
                maxSpeed = DragScrollMaxSpeed.toPx(),
            )
        }
    }
    drag.items = items
    LaunchedEffect(drag) {
        snapshotFlow { drag.speed }.collectLatest { speed ->
            if (speed == 0f) return@collectLatest
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                drag.scrollBy(speed * (now - last) / 1_000_000_000f)
                last = now
                drag.follow()
            }
        }
    }
    return drag
}

/**
 * After a long press on a tile, the finger selects instead of scrolling: its moves are taken in
 * the initial pass, before the grid's own scrolling and the tiles' clicks can see them.
 */
internal fun Modifier.dragSelection(drag: GridDragSelection): Modifier =
    pointerInput(drag) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            if (!drag.start(press.position)) return@awaitEachGesture
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == press.id } ?: break
                    change.consume()
                    if (change.changedToUpIgnoreConsumed()) break
                    drag.moveTo(change.position, size.height.toFloat())
                }
            } finally {
                drag.end()
            }
        }
    }

private val DragScrollEdge = 120.dp
private val DragScrollMaxSpeed = 1200.dp
