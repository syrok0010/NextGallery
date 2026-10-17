package com.syrok0010.nextgallery.feature.collection

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import com.syrok0010.nextgallery.core.media.MediaItem
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

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
    onUpload: (List<MediaItem>, onStarted: () -> Unit) -> Unit = { _, onStarted -> onStarted() },
    /** Hands over the selection snapshot; [onDone] ends selection mode once the action starts. */
    onAddToAlbum: ((List<MediaItem>, onDone: () -> Unit) -> Unit)? = null,
    /** Same contract as [onAddToAlbum]; [onDone] runs only once the deletion is confirmed. */
    onDelete: ((List<MediaItem>, onDone: () -> Unit) -> Unit)? = null,
    scaffold: @Composable (viewerVisible: Boolean, content: @Composable () -> Unit) -> Unit,
) {
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(slots, transition.viewerMediaId)
    val index = remember(slots) { ViewerMediaIndex(slots) }
    val viewerId = transition.viewerMediaId?.takeIf { it in sequence }
    val selection = rememberMediaSelection()
    var uploadRequested by remember { mutableStateOf(false) }
    val cancelSelection = {
        selection.clear()
        uploadRequested = false
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
    BackHandler(enabled = selection.active && !uploadRequested, onBack = cancelSelection)
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
                    gridState = gridState,
                )
            }
        }
        if (viewerId == null && selectionEnabled && selection.active) {
            SelectionActionBar(
                selectedCount = selectedItems.size,
                uploadRequested = uploadRequested,
                onCancel = cancelSelection,
                onAddToAlbum = onAddToAlbum?.let { action ->
                    { action(selectedItems, cancelSelection) }
                },
                onDelete = onDelete?.let { action ->
                    { action(selectedItems, cancelSelection) }
                },
                onUpload = {
                    uploadRequested = true
                    onUpload(selectedItems, cancelSelection)
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

@Composable
private fun BoxScope.SelectionActionBar(
    selectedCount: Int,
    uploadRequested: Boolean,
    onCancel: () -> Unit,
    onAddToAlbum: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onUpload: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = MaterialTheme.shapes.extraLarge,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = onCancel, enabled = !uploadRequested) {
                Icon(Icons.Default.Close, contentDescription = stringResource(com.syrok0010.nextgallery.R.string.selection_cancel))
            }
            val selectedLabel =
                stringResource(com.syrok0010.nextgallery.R.string.selection_count, selectedCount)
            Text(
                "$selectedCount",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .semantics { contentDescription = selectedLabel },
            )
            Spacer(Modifier.width(4.dp))
            if (onDelete != null) {
                IconButton(onClick = onDelete, enabled = selectedCount > 0 && !uploadRequested) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(
                            com.syrok0010.nextgallery.R.string.delete_action,
                        ),
                    )
                }
            }
            if (onAddToAlbum != null) {
                // Icon-only keeps the bar on one line next to the primary upload action.
                FilledTonalIconButton(
                    onClick = onAddToAlbum,
                    enabled = selectedCount > 0 && !uploadRequested,
                ) {
                    Icon(
                        painter = painterResource(com.syrok0010.nextgallery.R.drawable.ic_albums),
                        contentDescription = stringResource(
                            com.syrok0010.nextgallery.R.string.album_add_action,
                        ),
                    )
                }
            }
            Button(onClick = onUpload, enabled = selectedCount > 0 && !uploadRequested) {
                Icon(
                    painter = painterResource(com.syrok0010.nextgallery.R.drawable.ic_cloud),
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(com.syrok0010.nextgallery.R.string.upload_action))
            }
        }
    }
}
