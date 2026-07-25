package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.ui.NextGalleryScaffold
import com.syrok0010.nextgallery.ui.ViewerTransitionCoordinator
import com.syrok0010.nextgallery.ui.detail.MediaDetailScreen
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun HomeScreen(
    viewerTransitionCoordinator: ViewerTransitionCoordinator,
    viewModel: AuthenticatedViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val credentials = state.credentials ?: run {
        NextGalleryScaffold(showTopBar = false) { _ -> }
        return
    }
    val viewerTimeline = rememberViewerTimeline(state.timeline.snapshot)
    val visibleViewerFileId = viewerTransitionCoordinator.viewerFileId?.takeIf { fileId ->
        viewerTimeline.slotIndexByFileId.containsKey(fileId)
    }

    NextGalleryScaffold(
        showTopBar = visibleViewerFileId == null,
        actions = {
            TextButton(onClick = viewModel::refresh, enabled = !state.isBusy) {
                Text(stringResource(R.string.action_refresh))
            }
            TextButton(onClick = viewModel::logout, enabled = !state.isBusy) {
                Text(stringResource(R.string.action_logout))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .onGloballyPositioned { coordinates ->
                        viewerTransitionCoordinator.onAppBoundsChanged(coordinates.boundsInRoot())
                    },
            ) {
                TimelinePanel(
                    state = state.timeline,
                    message = state.message,
                    onViewportObservation = viewModel::observeTimelineViewport,
                    revealFileId = viewerTransitionCoordinator.revealFileId,
                    onFileRevealed = viewerTransitionCoordinator::onTimelineFileRevealed,
                    onTileBoundsChanged = viewerTransitionCoordinator::onTileBoundsChanged,
                    onSelect = { item -> viewerTransitionCoordinator.open(item.fileId) },
                )
            }

            if (state.isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(padding)
                        .padding(16.dp)
                        .size(28.dp),
                )
            }

            if (visibleViewerFileId != null) {
                MediaDetailScreen(
                    initialFileId = visibleViewerFileId,
                    items = viewerTimeline.items,
                    slotIndexByFileId = viewerTimeline.slotIndexByFileId,
                    tileBoundsByFileId = viewerTransitionCoordinator.visibleTimelineTileBoundsByFileId,
                    thumbnailKeys = state.timeline.thumbnailKeys,
                    credentials = credentials,
                    onBack = { currentItem -> viewerTransitionCoordinator.close(currentItem.fileId) },
                    onCurrentItemChange = { currentItem ->
                        viewerTransitionCoordinator.onCurrentItemChanged(currentItem.fileId)
                    },
                    onVisibleTimelineRange = viewModel::loadVisibleTimelineRange,
                )
            }
        }
    }
}
