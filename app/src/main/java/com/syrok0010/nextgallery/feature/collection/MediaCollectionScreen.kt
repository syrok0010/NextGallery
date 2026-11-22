package com.syrok0010.nextgallery.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.syrok0010.nextgallery.core.media.MediaId
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
    /** Owned by the caller so its actions can clear it; null disables selection mode. */
    selection: MediaSelection? = null,
    selectionActions: List<SelectionAction> = emptyList(),
    viewerActions: List<SelectionAction> = emptyList(),
    viewerStatus: @Composable (visible: Boolean) -> Unit = {},
    deletedMediaIds: Set<MediaId> = emptySet(),
    /** Running operations on a tile's object; reads snapshot state, see [MediaSlotTile]. */
    tileActivity: (MediaItem) -> MediaTileActivity? = { null },
    scaffold: @Composable (viewerVisible: Boolean, content: @Composable () -> Unit) -> Unit,
) {
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(slots, transition.viewerMediaId, deletedMediaIds)
    val index = remember(slots) { ViewerMediaIndex(slots) }
    // A deleted current object leaves the sequence; the open pager reports what took its place.
    val viewerId = transition.viewerMediaId?.takeIf { it in sequence || it in deletedMediaIds }
    val selectionActive = selection?.active == true
    SideEffect {
        onViewerVisibilityChanged(viewerId != null)
        onSelectionModeChanged(selectionActive)
    }
    DisposableEffect(Unit) {
        onDispose {
            onViewerVisibilityChanged(false)
            onSelectionModeChanged(false)
        }
    }
    BackHandler(enabled = selectionActive) { selection?.clear() }
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
                    selection = selection,
                    tileActivity = tileActivity,
                    gridState = gridState,
                )
            }
        }
        if (viewerId == null && selection != null && selection.active) {
            val selectedItems = remember(slots, selection.selectedIds) {
                slots.mapNotNull { it.mediaItem }.filter { selection.isSelected(it.mediaId) }
            }
            SelectionActionBar(
                selectedCount = selectedItems.size,
                onCancel = selection::clear,
                actions = selectionActions,
                onPerform = { it.perform(selectedItems, selection::clear) },
            )
        }
        if (viewerId != null) MediaDetailScreen(
            initialMediaId = viewerId,
            sequence = sequence,
            actions = if (viewerActions.isEmpty()) {
                null
            } else {
                { item -> ViewerSelectionActions(viewerActions, item) }
            },
            status = viewerStatus,
            tileBoundsForMediaId = transition::timelineTileBounds,
            onBack = { transition.close(it.mediaId, index.slotIndex(it.mediaId) != null) },
            onEmpty = { transition.close(viewerId, false) },
            onCurrentItemChange = { item ->
                index.prefetchRange(item.mediaId)?.let { onViewerRange(it) }
                transition.onCurrentItemChanged(item.mediaId, index.slotIndex(item.mediaId) != null)
            },
        )
    }
}
