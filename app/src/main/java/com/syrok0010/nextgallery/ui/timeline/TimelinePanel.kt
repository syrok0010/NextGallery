package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.TimelineUiState
import com.syrok0010.nextgallery.ui.asString
import com.syrok0010.nextgallery.ui.common.StatusBlock
import com.syrok0010.nextgallery.ui.uiText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TimelineScrollbarDragLoadDebounceMillis = 450L

@Composable
internal fun TimelinePanel(
    state: TimelineUiState,
    message: AppMessageUiState,
    credentials: AccountCredentials,
    onVisibleRange: (firstVisibleIndex: Int, lastVisibleIndex: Int) -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    val timeline = state.snapshot
    val gridState = rememberLazyGridState()
    val gridItems = remember(timeline?.slots) {
        timeline?.slots?.toTimelineGridItems().orEmpty()
    }
    val slotGridIndexes = remember(gridItems) {
        gridItems.toSlotGridIndexes()
    }
    val coroutineScope = rememberCoroutineScope()
    var isDraggingScrollIndicator by remember { mutableStateOf(false) }
    val scrollInfo by remember(gridItems) {
        derivedStateOf {
            val visibleSlot = gridState.layoutInfo.visibleItemsInfo
                .mapNotNull { visibleItem ->
                    (gridItems.getOrNull(visibleItem.index) as? TimelineGridItem.Slot)
                        ?.let { it.slotIndex to it.slot.dayId }
                }
                .minByOrNull { it.first }
            val totalSlots = timeline?.slots?.size ?: 0
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

    LaunchedEffect(gridItems) {
        if (timeline == null) {
            return@LaunchedEffect
        }

        var pendingDragLoadJob: Job? = null

        snapshotFlow {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val visibleSlotIndexes = visibleItems.mapNotNull { visibleItem ->
                (gridItems.getOrNull(visibleItem.index) as? TimelineGridItem.Slot)?.slotIndex
            }
            val firstSlotIndex = visibleSlotIndexes.minOrNull()
            val lastSlotIndex = visibleSlotIndexes.maxOrNull()
            TimelineVisibleRange(
                firstSlotIndex = firstSlotIndex,
                lastSlotIndex = lastSlotIndex,
                loadingMode = if (isDraggingScrollIndicator) {
                    TimelineVisibleRangeLoadingMode.Debounced
                } else {
                    TimelineVisibleRangeLoadingMode.Immediate
                },
            )
        }.collect {
            val visibleRange = it.takeIfReady() ?: return@collect

            when (visibleRange.loadingMode) {
                TimelineVisibleRangeLoadingMode.Immediate -> {
                    pendingDragLoadJob?.cancel()
                    pendingDragLoadJob = null
                    onVisibleRange(visibleRange.firstSlotIndex, visibleRange.lastSlotIndex)
                }

                TimelineVisibleRangeLoadingMode.Debounced -> {
                    pendingDragLoadJob?.cancel()
                    pendingDragLoadJob = launch {
                        delay(TimelineScrollbarDragLoadDebounceMillis)
                        onVisibleRange(visibleRange.firstSlotIndex, visibleRange.lastSlotIndex)
                        pendingDragLoadJob = null
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (timeline != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.timeline_summary,
                        timeline.memoriesVersion,
                        timeline.totalMediaCountHint,
                        timeline.totalDayCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                timeline.timelinePath?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        StatusBlock(message)
        TimelineLoadMoreStatus(state)

        if (timeline?.slots.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.timeline_empty))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 116.dp),
                    state = gridState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    itemsIndexed(
                        items = gridItems,
                        key = { _, item -> item.key },
                        span = { _, item ->
                            when (item) {
                                is TimelineGridItem.DayHeader -> GridItemSpan(maxLineSpan)
                                is TimelineGridItem.Slot -> GridItemSpan(1)
                            }
                        },
                    ) { _, item ->
                        when (item) {
                            is TimelineGridItem.DayHeader -> TimelineDayHeader(item.dayId)
                            is TimelineGridItem.Slot -> TimelineSlotTile(
                                slot = item.slot,
                                credentials = credentials,
                                onSelect = onSelect,
                            )
                        }
                    }
                }

                TimelineScrollIndicator(
                    dayId = scrollInfo.dayId,
                    fraction = scrollInfo.fraction,
                    isTooltipVisible = gridState.isScrollInProgress || isDraggingScrollIndicator,
                    onDragStateChange = { isDraggingScrollIndicator = it },
                    onFractionChange = { fraction ->
                        val targetGridIndex = slotGridIndexes.gridIndexAtFraction(fraction)
                        if (targetGridIndex != null) {
                            coroutineScope.launch {
                                gridState.scrollToItem(targetGridIndex)
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

private data class TimelineScrollInfo(
    val dayId: Int?,
    val fraction: Float,
)

private data class TimelineVisibleRange(
    val firstSlotIndex: Int?,
    val lastSlotIndex: Int?,
    val loadingMode: TimelineVisibleRangeLoadingMode,
)

private data class ReadyTimelineVisibleRange(
    val firstSlotIndex: Int,
    val lastSlotIndex: Int,
    val loadingMode: TimelineVisibleRangeLoadingMode,
)

private enum class TimelineVisibleRangeLoadingMode {
    Immediate,
    Debounced,
}

private fun TimelineVisibleRange.takeIfReady(): ReadyTimelineVisibleRange? {
    val firstSlotIndex = firstSlotIndex ?: return null
    val lastSlotIndex = lastSlotIndex ?: return null

    return ReadyTimelineVisibleRange(
        firstSlotIndex = firstSlotIndex,
        lastSlotIndex = lastSlotIndex,
        loadingMode = loadingMode,
    )
}

@Composable
private fun TimelineLoadMoreStatus(state: TimelineUiState) {
    val message = when {
        state.loadMoreError != null -> state.loadMoreError
        state.loadingDayIds.isNotEmpty() -> uiText(R.string.status_loading_timeline_batch)
        else -> null
    } ?: return
    val color = if (state.loadMoreError != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = message.asString(),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        color = color,
        style = MaterialTheme.typography.bodySmall,
    )
}
