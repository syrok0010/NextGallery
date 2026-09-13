package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.albums.AlbumsPanel
import com.syrok0010.nextgallery.feature.albums.AlbumOrigin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
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
    albumsViewModel: AlbumsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val albums by albumsViewModel.state.collectAsState()
    var page by rememberSaveable { mutableStateOf(LibraryPage.Photos) }
    var filter by rememberSaveable { mutableStateOf<AlbumOrigin?>(null) }
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val albumListState = rememberLazyListState()
    val savedPages = rememberSaveableStateHolder()
    LaunchedEffect(state.localMediaPermissionMode) {
        state.localMediaPermissionMode?.let(albumsViewModel::updatePermission)
    }
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

    LibrarySystemBars()
    NextGalleryTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.alpha(if (visibleViewerMediaId == null) 1f else 0f)) {
                        LibraryHeader(
                            page = page,
                            hasProblem = state.message.error != null || state.timeline.loadMoreError != null ||
                                albums.remote.failed || albums.local.failed,
                            onDiagnostics = { diagnostics = true },
                            onRefresh = { viewModel.refresh(); albumsViewModel.refresh() },
                            onLogout = viewModel::logout,
                        )
                    }
                    Box(Modifier.weight(1f).onGloballyPositioned {
                        viewerTransitionCoordinator.onAppBoundsChanged(it.boundsInRoot())
                    }) {
                        savedPages.SaveableStateProvider(page) {
                            when (page) {
                                LibraryPage.Photos -> TimelinePanel(
                                    state = state.timeline,
                                    onViewportObservation = viewModel::observeTimelineViewport,
                                    revealMediaId = viewerTransitionCoordinator.revealMediaId,
                                    onMediaRevealed = viewerTransitionCoordinator::onTimelineMediaRevealed,
                                    registerTimelineTile = viewerTransitionCoordinator::registerTimelineTile,
                                    onSelect = { item -> viewerTransitionCoordinator.open(item.mediaId) },
                                    gridState = gridState,
                                )
                                LibraryPage.Albums -> AlbumsPanel(albums, albumListState, filter, { filter = it }, albumsViewModel::refresh)
                            }
                        }
                    }
                }
                if (visibleViewerMediaId == null) {
                    LibraryIsland(page, { page = it }, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp))
                }
                if (diagnostics) LibraryDiagnostics(state, albums, onDismiss = { diagnostics = false })

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
}

@Composable
private fun LibrarySystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = generateSequence<Context>(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            oldStatus?.let { controller.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}
