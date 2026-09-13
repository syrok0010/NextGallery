package com.syrok0010.nextgallery.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.albums.AlbumOrigin
import com.syrok0010.nextgallery.feature.albums.AlbumsPanel
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.feature.auth.LoginScreen
import com.syrok0010.nextgallery.feature.timeline.LocalMediaPermissionFlow
import com.syrok0010.nextgallery.feature.timeline.TimelinePanel
import com.syrok0010.nextgallery.feature.timeline.TimelineViewModel
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun NextGalleryApp(
    sessionViewModel: SessionViewModel = koinViewModel(),
    viewModel: TimelineViewModel = koinViewModel(),
    permissionCoordinator: LocalMediaPermissionCoordinator = koinInject(),
    albumsViewModel: AlbumsViewModel = koinViewModel(),
) {
    val session by sessionViewModel.session.collectAsState()
    val viewerTransitionCoordinator = rememberViewerTransitionCoordinator()
    val state by viewModel.state.collectAsState()
    val albums by albumsViewModel.state.collectAsState()
    val backStack = rememberNavBackStack(session.rootRoute())
    // Normalize before rendering: a signed-out session must never show a restored library entry.
    val routes = syncedBackStack(backStack.toList(), session)
    val page = routes.last()
    val signedIn = session is SessionUiState.SignedIn
    LaunchedEffect(session) {
        viewerTransitionCoordinator.onSessionChanged(session)
        if (backStack.toList() != routes) {
            backStack.clear()
            backStack.addAll(routes)
        }
    }
    var filter by rememberSaveable(session) { mutableStateOf<AlbumOrigin?>(null) }
    var diagnostics by rememberSaveable(session) { mutableStateOf(false) }
    val gridState = key(session) { rememberLazyGridState() }
    val albumListState = key(session) { rememberLazyListState() }
    LaunchedEffect(state.localMediaPermissionMode) {
        state.localMediaPermissionMode?.let(albumsViewModel::updatePermission)
    }
    LocalMediaPermissionFlow(
        isSignedIn = signedIn,
        permissionMode = state.localMediaPermissionMode,
        permissionCoordinator = permissionCoordinator,
        onPermissionModeChanged = viewModel::onLocalMediaPermissionChanged,
    )
    val viewerSequence = rememberViewerSequence(
        snapshot = state.timeline.snapshot,
        currentMediaId = viewerTransitionCoordinator.viewerMediaId,
    )
    val timelineIndex = remember(state.timeline.snapshot) { ViewerTimelineIndex(state.timeline.snapshot) }
    val visibleViewerMediaId = viewerTransitionCoordinator.viewerMediaId?.takeIf { mediaId ->
        mediaId in viewerSequence
    }

    if (signedIn) LibrarySystemBars()
    NextGalleryTheme(darkTheme = signedIn || isSystemInDarkTheme(), dynamicColor = !signedIn) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                Column(Modifier.fillMaxSize().then(if (signedIn) Modifier.safeDrawingPadding() else Modifier)) {
                    if (signedIn) Box(Modifier.alpha(if (visibleViewerMediaId == null) 1f else 0f)) {
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
                        NavDisplay(
                            backStack = routes,
                            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                            transitionSpec = {
                                if (initialState.entries.last().contentKey == NextGalleryRoute.Photos.toString() &&
                                    targetState.entries.last().contentKey == NextGalleryRoute.Albums.toString()) {
                                    slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) togetherWith
                                        slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                                } else fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                            },
                            popTransitionSpec = {
                                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) togetherWith
                                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                            },
                            predictivePopTransitionSpec = {
                                slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) togetherWith
                                    slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                            },
                            entryProvider = entryProvider {
                                entry<NextGalleryRoute.Login> { LoginScreen() }
                                entry<NextGalleryRoute.Photos> {
                                    TimelinePanel(
                                        state = state.timeline,
                                        onViewportObservation = viewModel::observeTimelineViewport,
                                        revealMediaId = viewerTransitionCoordinator.revealMediaId,
                                        onMediaRevealed = viewerTransitionCoordinator::onTimelineMediaRevealed,
                                        registerTimelineTile = viewerTransitionCoordinator::registerTimelineTile,
                                        onSelect = { item -> viewerTransitionCoordinator.open(item.mediaId) },
                                        gridState = gridState,
                                    )
                                }
                                entry<NextGalleryRoute.Albums> {
                                    AlbumsPanel(albums, albumListState, filter, { filter = it }, albumsViewModel::refresh)
                                }
                            },
                        )
                    }
                }
                if (signedIn && visibleViewerMediaId == null) {
                    LibraryIsland(page, { destination ->
                        val target = destinationBackStack(destination)
                        if (backStack.toList() != target) {
                            if (destination == NextGalleryRoute.Photos) backStack.removeLastOrNull()
                            else backStack.add(NextGalleryRoute.Albums)
                        }
                    }, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp))
                }
                if (signedIn && diagnostics) LibraryDiagnostics(state, albums, onDismiss = { diagnostics = false })

                if (signedIn && visibleViewerMediaId != null) {
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
