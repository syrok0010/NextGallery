package com.syrok0010.nextgallery.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen

/** Shared grid, scroll restoration and viewer transitions; callers own loading and chrome. */
@Composable
internal fun MediaCollectionScreen(
    slots: List<MediaSlot>,
    emptyContent: @Composable () -> Unit,
    onViewportObservation: (MediaViewportObservation) -> Unit = {},
    onViewerRange: (IntRange) -> Unit = {},
    onViewerVisibilityChanged: (Boolean) -> Unit = {},
    onSelectionModeChanged: (Boolean) -> Unit = {},
    selectionEnabled: Boolean = false,
    selectionActions: List<SelectionAction> = emptyList(),
    /** Running operations on a tile's object; reads snapshot state, see [MediaSlotTile]. */
    tileActivity: (MediaItem) -> MediaTileActivity? = { null },
    scaffold: @Composable (viewerVisible: Boolean, content: @Composable () -> Unit) -> Unit,
) {
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(slots, transition.viewerMediaId)
    val index = remember(slots) { ViewerMediaIndex(slots) }
    val viewerId = transition.viewerMediaId?.takeIf { it in sequence }
    val selection = rememberMediaSelection()
    var selectionLocked by remember { mutableStateOf(false) }
    val cancelSelection = {
        selection.clear()
        selectionLocked = false
    }
    val selectedItems = remember(slots, selection.selectedIds) {
        slots.mapNotNull { it.mediaItem }.filter { selection.isSelected(it.mediaId) }
    }
    SideEffect {
        onViewerVisibilityChanged(viewerId != null)
        onSelectionModeChanged(selection.active)
    }
    DisposableEffect(Unit) {
        onDispose {
            onViewerVisibilityChanged(false)
            onSelectionModeChanged(false)
        }
    }
    BackHandler(enabled = selection.active && !selectionLocked, onBack = cancelSelection)
    Box(Modifier.fillMaxSize()) {
        scaffold(viewerId != null) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { transition.onAppBoundsChanged(it.boundsInRoot()) }) {
                MediaGridPanel(
                    slots = slots,
                    emptyContent = emptyContent,
                    onViewportObservation = onViewportObservation,
                    revealMediaId = transition.revealMediaId,
                    onMediaRevealed = transition::onTimelineMediaRevealed,
                    registerTimelineTile = transition::registerTimelineTile,
                    onSelect = { transition.open(it.mediaId) },
                    selection = selection.takeIf { selectionEnabled },
                    tileActivity = tileActivity,
                    gridState = gridState,
                )
            }
        }
        if (viewerId == null && selectionEnabled && selection.active) {
            SelectionActionBar(
                selectedCount = selectedItems.size,
                selectionLocked = selectionLocked,
                onCancel = cancelSelection,
                actions = selectionActions,
                onPerform = { action ->
                    selectionLocked = action.blocksSelectionUntilDone
                    action.perform(selectedItems, cancelSelection)
                },
            )
        }
        if (viewerId != null) MediaDetailScreen(
            initialMediaId = viewerId,
            sequence = sequence,
            tileBoundsForMediaId = transition::timelineTileBounds,
            onBack = { transition.close(it.mediaId, index.slotIndex(it.mediaId) != null) },
            onCurrentItemChange = { item ->
                index.prefetchRange(item.mediaId)?.let { onViewerRange(it) }
                transition.onCurrentItemChanged(item.mediaId, index.slotIndex(item.mediaId) != null)
            },
        )
    }
}
