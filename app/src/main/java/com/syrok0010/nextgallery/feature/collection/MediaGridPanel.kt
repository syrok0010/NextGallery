package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem

@Composable
internal fun MediaGridPanel(
    slots: List<MediaSlot>,
    emptyContent: @Composable () -> Unit,
    onViewportObservation: (MediaViewportObservation) -> Unit = {},
    revealMediaId: MediaId?,
    onMediaRevealed: () -> Unit,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
    selectionEnabled: Boolean = false,
    selectionMode: Boolean = false,
    selectedMediaIds: Set<MediaId> = emptySet(),
    onSelectionModeChanged: (Boolean) -> Unit = {},
    onMediaSelectionChanged: (MediaItem, Boolean) -> Unit = { _, _ -> },
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val gridItems = remember(slots) {
        slots.toMediaGridItems()
    }
    val slotGridIndexes = remember(gridItems) {
        gridItems.toSlotGridIndexes()
    }
    var isDraggingScrollIndicator by remember { mutableStateOf(false) }
    PreserveMediaScrollAnchor(
        gridItems = gridItems,
        gridState = gridState,
        isScrollNavigationActive = isDraggingScrollIndicator || revealMediaId != null,
    )

    LaunchedEffect(gridItems) {
        snapshotFlow {
            val visibleItems = gridState.layoutInfo.visibleItemsInfo
            val visibleSlotIndexes = visibleItems.mapNotNull { visibleItem ->
                (gridItems.getOrNull(visibleItem.index) as? MediaGridItem.Slot)?.slotIndex
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
                        MediaViewportObservation(
                            firstVisibleSlotIndex = visibleRange.firstSlotIndex,
                            lastVisibleSlotIndex = visibleRange.lastSlotIndex,
                            loadingMode = MediaViewportLoadingMode.Immediate,
                        ),
                    )
                }

                TimelineVisibleRangeLoadingMode.Debounced -> {
                    onViewportObservation(
                        MediaViewportObservation(
                            firstVisibleSlotIndex = visibleRange.firstSlotIndex,
                            lastVisibleSlotIndex = visibleRange.lastSlotIndex,
                            loadingMode = MediaViewportLoadingMode.Debounced,
                        ),
                    )
                }
            }
        }
    }

    LaunchedEffect(revealMediaId, gridItems) {
        val mediaId = revealMediaId ?: return@LaunchedEffect
        val targetGridIndex = gridItems.indexOfFirst { item ->
            item is MediaGridItem.Slot && item.slot.mediaItem?.mediaId == mediaId
        }
        if (targetGridIndex >= 0) {
            gridState.scrollToItem(targetGridIndex)
            onMediaRevealed()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (slots.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                emptyContent()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                MediaGrid(
                    gridItems = gridItems,
                    gridState = gridState,
                    registerTimelineTile = registerTimelineTile,
                    onSelect = onSelect,
                    selectionEnabled = selectionEnabled,
                    selectionMode = selectionMode,
                    selectedMediaIds = selectedMediaIds,
                    onSelectionModeChanged = onSelectionModeChanged,
                    onMediaSelectionChanged = onMediaSelectionChanged,
                )

                MediaScrollIndicatorHost(
                    slots = slots,
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
