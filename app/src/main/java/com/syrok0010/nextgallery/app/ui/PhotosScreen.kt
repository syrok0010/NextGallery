package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.syrok0010.nextgallery.feature.timeline.TimelinePanel
import com.syrok0010.nextgallery.feature.timeline.TimelineViewModel
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    viewModel: TimelineViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val transition = rememberViewerTransitionCoordinator()
    val gridState = rememberLazyGridState()
    val sequence = rememberViewerSequence(state.timeline.snapshot, transition.viewerMediaId)
    val index = remember(state.timeline.snapshot) { ViewerTimelineIndex(state.timeline.snapshot) }
    val viewerId = transition.viewerMediaId?.takeIf { it in sequence }
    SideEffect { onViewerVisibilityChanged(viewerId != null) }
    DisposableEffect(Unit) { onDispose { onViewerVisibilityChanged(false) } }
    Box(Modifier.fillMaxSize()) {
        LibraryScreenScaffold(NextGalleryRoute.Photos, onLogout, viewerVisible = viewerId != null) {
            Box(Modifier.fillMaxSize().onGloballyPositioned { transition.onAppBoundsChanged(it.boundsInRoot()) }) {
                TimelinePanel(
                    state.timeline, viewModel::observeTimelineViewport, transition.revealMediaId,
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
                index.prefetchRange(item.mediaId)?.let { viewModel.loadVisibleTimelineRange(it.first, it.last) }
                transition.onCurrentItemChanged(item.mediaId, index.slotIndex(item.mediaId) != null)
            },
        )
    }
}
