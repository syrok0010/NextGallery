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
    selection: MediaSelection? = null,
    tileActivity: (MediaItem) -> MediaTileActivity? = { null },
    gridState: LazyGridState = rememberLazyGridState(),
) {
    val layout = remember(slots, gridState) { MediaGridLayout(slots) { gridState.measuredItems() } }
    var isDraggingScrollIndicator by remember { mutableStateOf(false) }
    PreserveMediaScrollAnchor(
        gridItems = layout.items,
        gridState = gridState,
        isScrollNavigationActive = isDraggingScrollIndicator || revealMediaId != null,
    )

    LaunchedEffect(layout) {
        snapshotFlow { layout.visibleSlots()?.let { it to isDraggingScrollIndicator } }
            .collect { visible ->
                val (slotRange, dragging) = visible ?: return@collect
                onViewportObservation(
                    MediaViewportObservation(
                        firstVisibleSlotIndex = slotRange.first,
                        lastVisibleSlotIndex = slotRange.last,
                        // Fast scrolling passes many days: only where it stops is loaded.
                        loadingMode = if (dragging) {
                            MediaViewportLoadingMode.Debounced
                        } else {
                            MediaViewportLoadingMode.Immediate
                        },
                    ),
                )
            }
    }

    LaunchedEffect(revealMediaId, layout) {
        val mediaId = revealMediaId ?: return@LaunchedEffect
        val gridIndex = layout.gridIndexOf(mediaId) ?: return@LaunchedEffect
        gridState.scrollToItem(gridIndex)
        onMediaRevealed()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (slots.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                emptyContent()
            }
        } else {
            Box(modifier = Modifier.fillMaxSize()) {
                MediaGrid(
                    layout = layout,
                    gridState = gridState,
                    registerTimelineTile = registerTimelineTile,
                    onSelect = onSelect,
                    selection = selection,
                    tileActivity = tileActivity,
                )

                MediaScrollIndicatorHost(
                    layout = layout,
                    gridState = gridState,
                    isDragging = isDraggingScrollIndicator,
                    onDragStateChange = { isDraggingScrollIndicator = it },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}
