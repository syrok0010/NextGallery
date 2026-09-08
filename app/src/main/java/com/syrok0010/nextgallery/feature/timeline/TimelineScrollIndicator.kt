package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val TimelineScrollThumbHeight = 48.dp
private val TimelineScrollThumbWidth = 4.dp
private val TimelineScrollDragWidth = 40.dp

@Composable
internal fun TimelineScrollIndicator(
    dayId: () -> Int?,
    fraction: () -> Float,
    isTooltipVisible: () -> Boolean,
    onDragStateChange: (Boolean) -> Unit,
    onFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val thumbHeightPx = with(LocalDensity.current) {
        TimelineScrollThumbHeight.toPx()
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(150.dp)
            .padding(end = 8.dp),
    ) {
        val thumbOffset = Modifier.offset {
            val availableHeightPx = (maxHeight - TimelineScrollThumbHeight).roundToPx()
            IntOffset(
                x = 0,
                y = (availableHeightPx * fraction().coerceIn(0f, 1f)).roundToInt(),
            )
        }

        TimelineScrollTooltip(
            dayId = dayId,
            isVisible = isTooltipVisible,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .then(thumbOffset),
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxHeight()
                .width(TimelineScrollDragWidth)
                .pointerInput(thumbHeightPx) {
                    fun scrollFraction(y: Float): Float {
                        val availableHeight = (size.height - thumbHeightPx).coerceAtLeast(1f)
                        return ((y - thumbHeightPx / 2f) / availableHeight).coerceIn(0f, 1f)
                    }

                    detectDragGestures(
                        onDragStart = { offset ->
                            onDragStateChange(true)
                            onFractionChange(scrollFraction(offset.y))
                        },
                        onDragEnd = {
                            onDragStateChange(false)
                        },
                        onDragCancel = {
                            onDragStateChange(false)
                        },
                    ) { change, _ ->
                        onFractionChange(scrollFraction(change.position.y))
                        change.consume()
                    }
                },
            contentAlignment = Alignment.TopEnd,
        ) {
            Box(
                modifier = Modifier
                    .then(thumbOffset)
                    .width(TimelineScrollThumbWidth)
                    .height(TimelineScrollThumbHeight)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = MaterialTheme.shapes.extraSmall,
                    ),
            )
        }
    }
}

@Composable
private fun TimelineScrollTooltip(
    dayId: () -> Int?,
    isVisible: () -> Boolean,
    modifier: Modifier = Modifier,
) {
    if (!isVisible()) {
        return
    }
    val currentDayId = dayId() ?: return
    val date = remember(currentDayId) {
        LocalDate.ofEpochDay(currentDayId.toLong())
    }
    val currentYear = LocalDate.now().year
    val pattern = stringResource(
        if (date.year == currentYear) {
            R.string.timeline_scroll_date_current_year_pattern
        } else {
            R.string.timeline_scroll_date_with_year_pattern
        },
    )
    val formatter = remember(pattern) {
        DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
    }
    val label = remember(date, formatter) {
        date.format(formatter)
    }

    Text(
        text = label,
        modifier = modifier
            .padding(end = 16.dp)
            .background(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.inverseOnSurface,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
    )
}

@Composable
internal fun TimelineScrollIndicatorHost(
    timeline: TimelineSnapshot,
    gridItems: List<TimelineGridItem>,
    slotGridIndexes: IntArray,
    gridState: LazyGridState,
    isDragging: Boolean,
    onDragStateChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollDispatcher = remember(coroutineScope, gridState, slotGridIndexes) {
        TimelineHandleScrollDispatcher(
            scope = coroutineScope,
            gridState = gridState,
            slotGridIndexes = slotGridIndexes,
        )
    }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val scrollInfo = remember(timeline, gridItems, gridState) {
        derivedStateOf {
            val visibleSlot = gridState.layoutInfo.visibleItemsInfo
                .mapNotNull { visibleItem ->
                    (gridItems.getOrNull(visibleItem.index) as? TimelineGridItem.Slot)
                        ?.let { it.slotIndex to it.slot.dayId }
                }
                .minByOrNull { it.first }
            val totalSlots = timeline.slots.size
            val fraction = if (visibleSlot == null || totalSlots <= 1) {
                0f
            } else {
                visibleSlot.first.toFloat() / (totalSlots - 1).toFloat()
            }

            TimelineScrollInfo(
                dayId = visibleSlot?.second,
                fraction = fraction.coerceIn(0f, 1f),
            )
        }
    }
    val displayFraction = remember(scrollInfo) {
        {
            dragFraction ?: scrollInfo.value.fraction
        }
    }
    val displayDayIdState = remember(timeline, scrollInfo) {
        derivedStateOf {
            dragFraction
                ?.let { timeline.dayIdAtFraction(it) }
                ?: scrollInfo.value.dayId
        }
    }
    val displayDayId = remember(displayDayIdState) {
        { displayDayIdState.value }
    }
    val currentIsDragging = rememberUpdatedState(isDragging)
    val tooltipVisibleState = remember(gridState) {
        derivedStateOf {
            currentIsDragging.value || gridState.isScrollInProgress
        }
    }
    val isTooltipVisible = remember(tooltipVisibleState) {
        { tooltipVisibleState.value }
    }
    val currentOnDragStateChange = rememberUpdatedState(onDragStateChange)
    val handleDragStateChange: (Boolean) -> Unit = remember {
        { dragging ->
            if (!dragging) {
                dragFraction = null
            }
            currentOnDragStateChange.value(dragging)
        }
    }
    val handleFractionChange: (Float) -> Unit = remember(scrollDispatcher) {
        { fraction ->
            dragFraction = fraction
            scrollDispatcher.scrollToFraction(fraction)
        }
    }

    TimelineScrollIndicator(
        dayId = displayDayId,
        fraction = displayFraction,
        isTooltipVisible = isTooltipVisible,
        onDragStateChange = handleDragStateChange,
        onFractionChange = handleFractionChange,
        modifier = modifier,
    )
}

private data class TimelineScrollInfo(
    val dayId: Int?,
    val fraction: Float,
)

private fun TimelineSnapshot.dayIdAtFraction(fraction: Float): Int? {
    if (slots.isEmpty()) {
        return null
    }

    val slotIndex = ((slots.size - 1) * fraction.coerceIn(0f, 1f)).toInt()
    return slots.getOrNull(slotIndex)?.dayId
}

private class TimelineHandleScrollDispatcher(
    private val scope: CoroutineScope,
    private val gridState: LazyGridState,
    private val slotGridIndexes: IntArray,
) {
    private var activeJob: Job? = null
    private var pendingGridIndex: Int? = null
    private var lastRequestedGridIndex: Int? = null

    fun scrollToFraction(fraction: Float) {
        val targetGridIndex = slotGridIndexes.gridIndexAtFraction(fraction) ?: return
        if (targetGridIndex == lastRequestedGridIndex && activeJob?.isActive == true) {
            return
        }

        pendingGridIndex = targetGridIndex
        if (activeJob?.isActive == true) {
            return
        }

        activeJob = scope.launch {
            while (true) {
                val nextGridIndex = pendingGridIndex ?: break
                pendingGridIndex = null
                lastRequestedGridIndex = nextGridIndex
                gridState.scrollToItem(nextGridIndex)
            }
        }
    }
}
