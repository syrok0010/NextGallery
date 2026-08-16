package com.syrok0010.nextgallery.ui.timeline

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onLocalMediaPermissionRequestCompleted()
    }
    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        viewModel.refreshLocalPermission()
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshLocalPermission()
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
                    localMediaActionEnabled = !state.localMedia.isLoading,
                    onShowLocalMedia = {
                        viewModel.showLocalMedia(
                            requiresSettings = activity?.let(permissionCoordinator::requiresSettings) == true,
                        )
                    },
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

            LocalMediaPermissionDialog(
                prompt = state.localMedia.prompt,
                onDismiss = viewModel::dismissLocalMediaPrompt,
                onRequestPermission = {
                    permissionLauncher.launch(permissionCoordinator.requestedPermissions())
                },
                onOpenSettings = {
                    settingsLauncher.launch(permissionCoordinator.settingsIntent())
                },
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
