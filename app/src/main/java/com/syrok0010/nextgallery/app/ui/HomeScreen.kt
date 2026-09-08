package com.syrok0010.nextgallery.app.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.NextGalleryScaffold
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedViewModel
import com.syrok0010.nextgallery.feature.timeline.LocalMediaPermissionFlow
import com.syrok0010.nextgallery.feature.timeline.TimelinePanel
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun HomeScreen(
    viewerTransitionCoordinator: ViewerTransitionCoordinator,
    viewModel: AuthenticatedViewModel = koinViewModel(),
    permissionCoordinator: LocalMediaPermissionCoordinator = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    LocalMediaPermissionFlow(
        isSignedIn = state.isSignedIn,
        permissionMode = state.localMediaPermissionMode,
        permissionCoordinator = permissionCoordinator,
        onPermissionModeChanged = viewModel::onLocalMediaPermissionChanged,
    )
    if (!state.isSignedIn) {
        NextGalleryScaffold(showTopBar = false) { _ -> }
        return
    }
    val viewerSequence = rememberViewerSequence(
        snapshot = state.timeline.snapshot,
        currentMediaId = viewerTransitionCoordinator.viewerMediaId,
    )
    val timelineIndex = remember(state.timeline.snapshot) { ViewerTimelineIndex(state.timeline.snapshot) }
    val visibleViewerMediaId = viewerTransitionCoordinator.viewerMediaId?.takeIf { mediaId ->
        mediaId in viewerSequence
    }

    NextGalleryScaffold(
        showTopBar = visibleViewerMediaId == null,
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
                    revealMediaId = viewerTransitionCoordinator.revealMediaId,
                    onMediaRevealed = viewerTransitionCoordinator::onTimelineMediaRevealed,
                    registerTimelineTile = viewerTransitionCoordinator::registerTimelineTile,
                    onSelect = { item ->
                        viewerTransitionCoordinator.open(item.mediaId)
                    },
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

            if (visibleViewerMediaId != null) {
                MediaDetailScreen(
                    initialMediaId = visibleViewerMediaId,
                    sequence = viewerSequence,
                    tileBoundsForMediaId = viewerTransitionCoordinator::timelineTileBounds,
                    onBack = { currentItem ->
                        viewerTransitionCoordinator.close(
                            mediaId = currentItem.mediaId,
                            isTimelineTargetAvailable =
                                timelineIndex.slotIndex(currentItem.mediaId) != null,
                        )
                    },
                    onCurrentItemChange = { currentItem ->
                        timelineIndex.prefetchRange(currentItem.mediaId)?.let { range ->
                            viewModel.loadVisibleTimelineRange(range.first, range.last)
                        }
                        viewerTransitionCoordinator.onCurrentItemChanged(
                            mediaId = currentItem.mediaId,
                            isTimelineTargetAvailable =
                                timelineIndex.slotIndex(currentItem.mediaId) != null,
                        )
                    },
                )
            }
        }
    }
}
