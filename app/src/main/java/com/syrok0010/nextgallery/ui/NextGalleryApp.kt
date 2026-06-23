package com.syrok0010.nextgallery.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.ui.auth.LoginDestinationScreen
import com.syrok0010.nextgallery.ui.timeline.AuthenticatedDestinationScreen
import org.koin.androidx.compose.koinViewModel

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun NextGalleryApp(
    viewModel: MainViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backStack = rememberNavBackStack(state.session.rootRoute())
    var revealTimelineFileId by remember { mutableStateOf<Long?>(null) }
    var viewerFileId by remember { mutableStateOf<Long?>(null) }
    var appBounds by remember { mutableStateOf<Rect?>(null) }
    val timelineTileBoundsByFileId = remember { mutableStateMapOf<Long, Rect>() }

    LaunchedEffect(state.session) {
        if (state.session !is SessionUiState.SignedIn) {
            revealTimelineFileId = null
            viewerFileId = null
        }

        val expectedBackStack = syncedBackStack(
            currentBackStack = backStack.toList(),
            session = state.session,
        )
        if (backStack != expectedBackStack) {
            backStack.clear()
            backStack.addAll(expectedBackStack)
        }
    }

    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            sharedTransitionScope = this,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryProvider = entryProvider {
                entry<NextGalleryRoute.Login> {
                    LoginDestinationScreen(
                        session = state.session as? SessionUiState.SignedOut,
                        message = state.message,
                        isBusy = state.isBusy,
                        onServerUrlChange = viewModel::updateServerUrl,
                        onStartLogin = viewModel::startLogin,
                        onLoginBrowserOpened = viewModel::markLoginBrowserOpened,
                        onLoginBrowserOpenFailed = viewModel::reportLoginBrowserOpenFailure,
                        onCancelLogin = viewModel::cancelLogin,
                    )
                }

                entry<NextGalleryRoute.Authenticated> {
                    AuthenticatedDestinationScreen(
                        session = state.session as? SessionUiState.SignedIn,
                        message = state.message,
                        isBusy = state.isBusy,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                        revealFileId = revealTimelineFileId,
                        viewerFileId = viewerFileId,
                        appBounds = appBounds,
                        timelineTileBoundsByFileId = timelineTileBoundsByFileId,
                        onRefresh = viewModel::refresh,
                        onLogout = {
                            viewerFileId = null
                            viewModel.logout()
                        },
                        onViewportObservation = viewModel::observeTimelineViewport,
                        onTimelineFileRevealed = { revealTimelineFileId = null },
                        onTileBoundsChanged = { fileId, bounds ->
                            if (bounds == null) {
                                timelineTileBoundsByFileId.remove(fileId)
                            } else {
                                timelineTileBoundsByFileId[fileId] = bounds
                            }
                        },
                        onSelect = { item ->
                            revealTimelineFileId = null
                            viewerFileId = item.fileId
                        },
                        onViewerFileIdChange = { viewerFileId = it },
                        onRevealTimelineFile = { revealTimelineFileId = it },
                        onVisibleTimelineRange = viewModel::loadVisibleTimelineRange,
                        onAppBoundsChanged = { appBounds = it },
                    )
                }
            },
        )
    }
}
