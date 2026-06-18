package com.syrok0010.nextgallery.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.auth.LoginPanel
import com.syrok0010.nextgallery.ui.detail.MediaDetailScreen
import com.syrok0010.nextgallery.ui.detail.MissingMediaDetailScreen
import com.syrok0010.nextgallery.ui.timeline.TimelinePanel
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel

@Serializable
private sealed interface NextGalleryRoute : NavKey {
    @Serializable
    data object Home : NextGalleryRoute

    @Serializable
    data class Detail(val fileId: Long) : NextGalleryRoute
}

@Composable
fun NextGalleryApp(
    viewModel: MainViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val backStack = rememberNavBackStack(NextGalleryRoute.Home)

    LaunchedEffect(state.session) {
        if (state.session !is SessionUiState.SignedIn) {
            while (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = {
            if (backStack.size > 1) {
                backStack.removeLastOrNull()
            }
        },
        entryProvider = entryProvider {
            entry<NextGalleryRoute.Home> {
                NextGalleryHomeScreen(
                    state = state,
                    onServerUrlChange = viewModel::updateServerUrl,
                    onStartLogin = viewModel::startLogin,
                    onLoginBrowserOpened = viewModel::markLoginBrowserOpened,
                    onLoginBrowserOpenFailed = viewModel::reportLoginBrowserOpenFailure,
                    onCancelLogin = viewModel::cancelLogin,
                    onRefresh = viewModel::refresh,
                    onLogout = viewModel::logout,
                    onVisibleTimelineRange = viewModel::loadVisibleTimelineRange,
                    onSelect = { item -> backStack.add(NextGalleryRoute.Detail(item.fileId)) },
                )
            }

            entry<NextGalleryRoute.Detail> { route ->
                val signedIn = state.session as? SessionUiState.SignedIn
                val item = signedIn?.timeline?.snapshot?.items?.firstOrNull { it.fileId == route.fileId }

                if (signedIn != null && item != null) {
                    MediaDetailScreen(
                        item = item,
                        credentials = signedIn.credentials,
                        onBack = { backStack.removeLastOrNull() },
                    )
                } else {
                    MissingMediaDetailScreen(onBack = { backStack.removeLastOrNull() })
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NextGalleryHomeScreen(
    state: MainUiState,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    onLoginBrowserOpened: () -> Unit,
    onLoginBrowserOpenFailed: () -> Unit,
    onCancelLogin: () -> Unit,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onVisibleTimelineRange: (firstVisibleIndex: Int, lastVisibleIndex: Int) -> Unit,
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
                        onVisibleRange = onVisibleTimelineRange,
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
