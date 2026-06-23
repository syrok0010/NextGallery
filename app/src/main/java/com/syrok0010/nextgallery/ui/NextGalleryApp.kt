package com.syrok0010.nextgallery.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.auth.LoginPanel
import com.syrok0010.nextgallery.ui.detail.MediaDetailScreen
import com.syrok0010.nextgallery.ui.timeline.TimelinePanel
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportObservation
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
private sealed interface NextGalleryRoute : NavKey {
    @Serializable
    data object Home : NextGalleryRoute
}

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun NextGalleryApp(
    viewModel: MainViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backStack = rememberNavBackStack(NextGalleryRoute.Home)
    var revealTimelineFileId by remember { mutableStateOf<Long?>(null) }
    var viewerFileId by remember { mutableStateOf<Long?>(null) }
    var appBounds by remember { mutableStateOf<Rect?>(null) }
    val timelineTileBoundsByFileId = remember { mutableStateMapOf<Long, Rect>() }

    LaunchedEffect(state.session) {
        if (state.session !is SessionUiState.SignedIn) {
            revealTimelineFileId = null
            viewerFileId = null
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
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
                entry<NextGalleryRoute.Home> {
                    val signedIn = state.session as? SessionUiState.SignedIn
                    val viewerItems = signedIn?.timeline?.snapshot
                        ?.slots
                        ?.mapIndexedNotNull { slotIndex, slot ->
                            slot.mediaItem?.let { item ->
                                ViewerMediaItem(
                                    item = item,
                                    slotIndex = slotIndex,
                                )
                            }
                        }
                        .orEmpty()
                    val items = viewerItems.map { it.item }
                    val slotIndexByFileId = viewerItems.associate { it.item.fileId to it.slotIndex }
                    val visibleViewerFileId = viewerFileId?.takeIf { fileId ->
                        items.any { it.fileId == fileId }
                    }
                    val navAnimatedVisibilityScope = LocalNavAnimatedContentScope.current

                    fun isTimelineTileVisible(fileId: Long): Boolean {
                        val tileBounds = timelineTileBoundsByFileId[fileId] ?: return false
                        val rootBounds = appBounds ?: return true
                        return tileBounds.overlaps(rootBounds)
                    }
                    val visibleTimelineTileBoundsByFileId = timelineTileBoundsByFileId
                        .filterValues { tileBounds ->
                            appBounds?.let { rootBounds -> tileBounds.overlaps(rootBounds) } ?: true
                        }

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .onGloballyPositioned { coordinates ->
                                appBounds = coordinates.boundsInRoot()
                            },
                    ) {
                        NextGalleryHomeScreen(
                            state = state,
                            sharedTransitionScope = this@SharedTransitionLayout,
                            animatedVisibilityScope = navAnimatedVisibilityScope,
                            enableTimelineSharedElements = visibleViewerFileId == null,
                            onServerUrlChange = viewModel::updateServerUrl,
                            onStartLogin = viewModel::startLogin,
                            onLoginBrowserOpened = viewModel::markLoginBrowserOpened,
                            onLoginBrowserOpenFailed = viewModel::reportLoginBrowserOpenFailure,
                            onCancelLogin = viewModel::cancelLogin,
                            onRefresh = viewModel::refresh,
                            onLogout = {
                                viewerFileId = null
                                viewModel.logout()
                            },
                            onViewportObservation = viewModel::observeTimelineViewport,
                            revealFileId = revealTimelineFileId,
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
                        )

                        if (signedIn != null && visibleViewerFileId != null) {
                            MediaDetailScreen(
                                initialFileId = visibleViewerFileId,
                                items = items,
                                slotIndexByFileId = slotIndexByFileId,
                                tileBoundsByFileId = visibleTimelineTileBoundsByFileId,
                                thumbnailPreviews = signedIn.timeline.thumbnailPreviews,
                                credentials = signedIn.credentials,
                                sharedTransitionScope = this@SharedTransitionLayout,
                                animatedVisibilityScope = navAnimatedVisibilityScope,
                                onBack = { currentItem ->
                                    if (!isTimelineTileVisible(currentItem.fileId)) {
                                        revealTimelineFileId = currentItem.fileId
                                    }
                                    viewerFileId = null
                                },
                                onCurrentItemChange = { currentItem ->
                                    if (!isTimelineTileVisible(currentItem.fileId)) {
                                        revealTimelineFileId = currentItem.fileId
                                    }
                                    viewerFileId = currentItem.fileId
                                },
                                onVisibleTimelineRange = viewModel::loadVisibleTimelineRange,
                            )
                        }
                    }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextGalleryHomeScreen(
    state: MainUiState,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    enableTimelineSharedElements: Boolean,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    onLoginBrowserOpened: () -> Unit,
    onLoginBrowserOpenFailed: () -> Unit,
    onCancelLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onViewportObservation: (TimelineViewportObservation) -> Unit,
    revealFileId: Long?,
    onTimelineFileRevealed: () -> Unit,
    onTileBoundsChanged: (fileId: Long, bounds: Rect?) -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.session is SessionUiState.SignedIn) {
                        TextButton(onClick = onRefresh, enabled = !state.isBusy) {
                            Text(stringResource(R.string.action_refresh))
                        }
                        TextButton(onClick = onLogout, enabled = !state.isBusy) {
                            Text(stringResource(R.string.action_logout))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when (val session = state.session) {
                is SessionUiState.SignedOut -> {
                    LoginPanel(
                        state = session.login,
                        message = state.message,
                        isBusy = state.isBusy,
                        onServerUrlChange = onServerUrlChange,
                        onStartLogin = onStartLogin,
                        onLoginBrowserOpened = onLoginBrowserOpened,
                        onLoginBrowserOpenFailed = onLoginBrowserOpenFailed,
                        onCancelLogin = onCancelLogin,
                    )
                }

                is SessionUiState.SignedIn -> {
                    TimelinePanel(
                        state = session.timeline,
                        message = state.message,
                        credentials = session.credentials,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        enableSharedElements = enableTimelineSharedElements,
                        onViewportObservation = onViewportObservation,
                        revealFileId = revealFileId,
                        onFileRevealed = onTimelineFileRevealed,
                        onTileBoundsChanged = onTileBoundsChanged,
                        onSelect = onSelect,
                    )
                }
            }

            if (state.isBusy || state.isLoginPolling) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .size(28.dp),
                )
            }
        }
    }
}

private data class ViewerMediaItem(
    val item: MediaItem,
    val slotIndex: Int,
)
