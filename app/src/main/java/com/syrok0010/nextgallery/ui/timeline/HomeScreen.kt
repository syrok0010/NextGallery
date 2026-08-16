package com.syrok0010.nextgallery.ui.timeline

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.ui.NextGalleryScaffold
import com.syrok0010.nextgallery.ui.ViewerTransitionCoordinator
import com.syrok0010.nextgallery.ui.detail.MediaDetailScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

@Composable
internal fun HomeScreen(
    viewerTransitionCoordinator: ViewerTransitionCoordinator,
    viewModel: AuthenticatedViewModel = koinViewModel(),
    permissionCoordinator: LocalMediaPermissionCoordinator = koinInject(),
) {
    val state by viewModel.state.collectAsState()
    var showPermissionExplanation by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onLocalMediaPermissionChanged(permissionCoordinator.currentMode())
    }
    LaunchedEffect(state.credentials) {
        showPermissionExplanation = false
        if (state.credentials != null) {
            viewModel.onLocalMediaPermissionChanged(permissionCoordinator.currentMode())
        }
    }
    LaunchedEffect(state.credentials, state.localMediaPermissionMode) {
        if (
            state.credentials != null &&
            state.localMediaPermissionMode != null &&
            permissionCoordinator.shouldExplainAutomatically()
        ) {
            permissionCoordinator.markAutomaticExplanationShown()
            showPermissionExplanation = true
        }
    }
    DisposableEffect(lifecycleOwner, state.credentials) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (state.credentials != null) {
                    viewModel.onLocalMediaPermissionChanged(permissionCoordinator.currentMode())
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val credentials = state.credentials ?: run {
        NextGalleryScaffold(showTopBar = false) { _ -> }
        return
    }
    val viewerTimeline = rememberViewerTimeline(state.timeline.snapshot)
    val visibleViewerMediaId = viewerTransitionCoordinator.viewerMediaId?.takeIf { mediaId ->
        viewerTimeline.slotIndexByMediaId.containsKey(mediaId)
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
                    credentials = credentials,
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
                    items = viewerTimeline.items,
                    slotIndexByMediaId = viewerTimeline.slotIndexByMediaId,
                    tileBoundsForMediaId = viewerTransitionCoordinator::timelineTileBounds,
                    credentials = credentials,
                    onBack = { currentItem -> viewerTransitionCoordinator.close(currentItem.mediaId) },
                    onCurrentItemChange = { currentItem ->
                        viewerTransitionCoordinator.onCurrentItemChanged(currentItem.mediaId)
                    },
                    onVisibleTimelineRange = viewModel::loadVisibleTimelineRange,
                )
            }

            LocalMediaPermissionExplanationDialog(
                visible = showPermissionExplanation,
                onDismiss = { showPermissionExplanation = false },
                onRequestPermission = {
                    showPermissionExplanation = false
                    permissionLauncher.launch(permissionCoordinator.requestedPermissions())
                },
            )
        }
    }
}
