package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.syrok0010.nextgallery.feature.timeline.MediaGridPanel
import com.syrok0010.nextgallery.feature.timeline.TimelineSlot
import com.syrok0010.nextgallery.feature.timeline.TimelineViewportObservation
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen

/** Shared grid, scroll restoration and viewer transitions; callers own loading and chrome. */
@Composable
internal fun MediaCollectionScreen(
    slots: List<TimelineSlot>,
    emptyContent: @Composable () -> Unit,
    onViewportObservation: (TimelineViewportObservation) -> Unit = {},
    onViewerRange: (IntRange) -> Unit = {},
    accessAllowed: Boolean = true,
    onViewerVisibilityChanged: (Boolean) -> Unit = {},
    scaffold: @Composable (viewerVisible: Boolean, content: @Composable () -> Unit) -> Unit,
) {
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(slots, transition.viewerMediaId)
    val index = remember(slots) { ViewerTimelineIndex(slots) }
    val viewerId = transition.viewerMediaId?.takeIf { accessAllowed && it in sequence }
    SideEffect { onViewerVisibilityChanged(viewerId != null) }
    DisposableEffect(Unit) { onDispose { onViewerVisibilityChanged(false) } }
    LaunchedEffect(accessAllowed) {
        if (!accessAllowed) transition.viewerMediaId?.let { transition.close(it, false) }
    }
    Box(Modifier.fillMaxSize()) {
        scaffold(viewerId != null) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { transition.onAppBoundsChanged(it.boundsInRoot()) }) {
                MediaGridPanel(
                    slots, emptyContent, onViewportObservation, transition.revealMediaId,
                    transition::onTimelineMediaRevealed, transition::registerTimelineTile,
                    { transition.open(it.mediaId) }, gridState,
                )
            }
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
