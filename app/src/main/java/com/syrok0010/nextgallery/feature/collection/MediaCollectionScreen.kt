package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
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
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import com.syrok0010.nextgallery.core.media.MediaId
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
    selectionEnabled: Boolean = false,
    onUpload: (List<MediaItem>) -> Unit = {},
    scaffold: @Composable (viewerVisible: Boolean, content: @Composable () -> Unit) -> Unit,
) {
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(slots, transition.viewerMediaId)
    val index = remember(slots) { ViewerMediaIndex(slots) }
    val viewerId = transition.viewerMediaId?.takeIf { it in sequence }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf(emptySet<MediaId>()) }
    val selectedItems = remember(slots, selectedMediaIds) {
        slots.mapNotNull { it.mediaItem }.filter { it.mediaId in selectedMediaIds }
    }
    SideEffect { onViewerVisibilityChanged(viewerId != null) }
    DisposableEffect(Unit) { onDispose { onViewerVisibilityChanged(false) } }
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
                    selectionMode = selectionMode,
                    selectionEnabled = selectionEnabled,
                    selectedMediaIds = selectedMediaIds,
                    onSelectionModeChanged = { selectionMode = it },
                    onMediaSelectionChanged = { item, selected ->
                        selectedMediaIds = if (selected) {
                            selectedMediaIds + item.mediaId
                        } else {
                            selectedMediaIds - item.mediaId
                        }
                    },
                    gridState = gridState,
                )
            }
        }
        if (viewerId == null && selectionEnabled && selectionMode) {
            SelectionActionBar(
                selectedCount = selectedItems.size,
                onCancel = {
                    selectionMode = false
                    selectedMediaIds = emptySet()
                },
                onUpload = {
                    onUpload(selectedItems)
                    selectionMode = false
                    selectedMediaIds = emptySet()
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
    onCancel: () -> Unit,
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
            IconButton(onClick = onCancel) {
                Icon(Icons.Default.Close, contentDescription = "Отменить выбор")
            }
            Text("Выбрано: $selectedCount", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(8.dp))
            Button(onClick = onUpload, enabled = selectedCount > 0) {
                Icon(
                    painter = painterResource(com.syrok0010.nextgallery.R.drawable.ic_cloud),
                    contentDescription = null,
                )
                Spacer(Modifier.width(6.dp))
                Text("Загрузить")
            }
        }
    }
}
