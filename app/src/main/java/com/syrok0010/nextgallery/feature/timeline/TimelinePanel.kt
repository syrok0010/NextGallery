package com.syrok0010.nextgallery.feature.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem

@Composable
internal fun TimelinePanel(
    state: TimelineUiState,
    onViewportObservation: (TimelineViewportObservation) -> Unit,
    revealMediaId: MediaId?,
    onMediaRevealed: () -> Unit,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val timeline = state.snapshot
    val gridItems = remember(timeline?.slots) {
        timeline?.slots?.toTimelineGridItems().orEmpty()
    }
    val slotGridIndexes = remember(gridItems) {
        gridItems.toSlotGridIndexes()
    }
    var isDraggingScrollIndicator by remember { mutableStateOf(false) }
    PreserveTimelineScrollAnchor(
        gridItems = gridItems,
        gridState = gridState,
        isScrollNavigationActive = isDraggingScrollIndicator || revealMediaId != null,
    )

    LaunchedEffect(gridItems) {
        if (timeline == null) {
            return@LaunchedEffect
        }

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
                    onViewportObservation(
                        TimelineViewportObservation(
                            firstVisibleSlotIndex = visibleRange.firstSlotIndex,
                            lastVisibleSlotIndex = visibleRange.lastSlotIndex,
                            loadingMode = TimelineViewportLoadingMode.Immediate,
                        ),
                    )
                }

                TimelineVisibleRangeLoadingMode.Debounced -> {
                    onViewportObservation(
                        TimelineViewportObservation(
                            firstVisibleSlotIndex = visibleRange.firstSlotIndex,
                            lastVisibleSlotIndex = visibleRange.lastSlotIndex,
                            loadingMode = TimelineViewportLoadingMode.Debounced,
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(revealMediaId, gridItems) {
        val mediaId = revealMediaId ?: return@LaunchedEffect
        val targetGridIndex = gridItems.indexOfFirst { item ->
            item is TimelineGridItem.Slot && item.slot.mediaItem?.mediaId == mediaId
        }
        if (targetGridIndex >= 0) {
            gridState.scrollToItem(targetGridIndex)
            onMediaRevealed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (timeline?.slots.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.timeline_empty))
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                TimelineGrid(
                    gridItems = gridItems,
                    gridState = gridState,
                    registerTimelineTile = registerTimelineTile,
                    onSelect = onSelect,
                )

                TimelineScrollIndicatorHost(
                    timeline = timeline,
                    gridItems = gridItems,
                    slotGridIndexes = slotGridIndexes,
                    gridState = gridState,
                    isDragging = isDraggingScrollIndicator,
                    onDragStateChange = { isDraggingScrollIndicator = it },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

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
