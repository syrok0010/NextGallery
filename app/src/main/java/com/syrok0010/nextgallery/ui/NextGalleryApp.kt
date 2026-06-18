package com.syrok0010.nextgallery.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import kotlinx.serialization.Serializable
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import org.koin.androidx.compose.koinViewModel
import java.time.format.DateTimeFormatter
import okhttp3.Credentials as OkHttpCredentials

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

    LaunchedEffect(state.credentials) {
        if (state.credentials == null) {
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
                    onSelect = { item -> backStack.add(NextGalleryRoute.Detail(item.fileId)) },
                )
            }

            entry<NextGalleryRoute.Detail> { route ->
                val credentials = state.credentials
                val item = state.timeline?.items?.firstOrNull { it.fileId == route.fileId }

                if (credentials != null && item != null) {
                    MediaDetail(
                        item = item,
                        credentials = credentials,
                        onBack = { backStack.removeLastOrNull() },
                    )
                } else {
                    MissingMediaDetail(onBack = { backStack.removeLastOrNull() })
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
    onSelect: (MediaItem) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    if (state.credentials != null) {
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
            if (state.credentials == null) {
                LoginPanel(
                    state = state,
                    onServerUrlChange = onServerUrlChange,
                    onStartLogin = onStartLogin,
                    onLoginBrowserOpened = onLoginBrowserOpened,
                    onLoginBrowserOpenFailed = onLoginBrowserOpenFailed,
                    onCancelLogin = onCancelLogin,
                )
            } else {
                TimelinePanel(
                    state = state,
                    credentials = state.credentials,
                    onSelect = onSelect,
                )
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

@Composable
private fun MissingMediaDetail(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.detail_missing_item),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
private fun LoginPanel(
    state: MainUiState,
    onServerUrlChange: (String) -> Unit,
    onStartLogin: () -> Unit,
    onLoginBrowserOpened: () -> Unit,
    onLoginBrowserOpenFailed: () -> Unit,
    onCancelLogin: () -> Unit,
) {
    val context = LocalContext.current
    val session = state.loginSession
    val openLoginUrl: (String) -> Unit = { loginUrl ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, loginUrl.toUri()))
        }.onSuccess {
            onLoginBrowserOpened()
        }.onFailure {
            onLoginBrowserOpenFailed()
        }
    }

    val loginUrlToOpen = session?.loginUrl?.takeUnless { state.loginBrowserOpened }
    LaunchedEffect(loginUrlToOpen) {
        if (loginUrlToOpen != null) {
            openLoginUrl(loginUrlToOpen)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        OutlinedTextField(
            value = state.serverUrlInput,
            onValueChange = onServerUrlChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text(stringResource(R.string.login_server_url_label)) },
            placeholder = { Text(stringResource(R.string.login_server_url_placeholder)) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = onStartLogin,
                enabled = !state.isBusy,
            ) {
                Text(
                    stringResource(
                        if (session == null) {
                            R.string.action_start_login
                        } else {
                            R.string.action_restart_login
                        },
                    ),
                )
            }

            if (session != null) {
                Button(
                    onClick = { openLoginUrl(session.loginUrl) },
                    enabled = !state.isBusy,
                ) {
                    Text(stringResource(R.string.action_open_browser))
                }
            }
        }

        if (session != null) {
            TextButton(
                onClick = onCancelLogin,
                enabled = !state.isBusy,
            ) {
                Text(stringResource(R.string.action_cancel_login))
            }
        }

        StatusBlock(state)
    }
}

@Composable
private fun TimelinePanel(
    state: MainUiState,
    credentials: AccountCredentials,
    onSelect: (MediaItem) -> Unit,
) {
    val timeline = state.timeline

    Column(modifier = Modifier.fillMaxSize()) {
        if (timeline != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = stringResource(
                        R.string.timeline_summary,
                        timeline.memoriesVersion,
                        timeline.totalMediaCountHint,
                        timeline.totalDayCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                timeline.timelinePath?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        StatusBlock(state)

        if (timeline?.items.isNullOrEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.timeline_empty))
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 116.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(
                    items = timeline.items,
                    key = { it.fileId },
                ) { item ->
                    MediaTile(
                        item = item,
                        credentials = credentials,
                        onClick = { onSelect(item) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaTile(
    item: MediaItem,
    credentials: AccountCredentials,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
    ) {
        AuthenticatedImage(
            url = item.thumbnailUrl,
            credentials = credentials,
            contentDescription = item.displayName,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )

        if (item.isVideo) {
            Text(
                text = stringResource(R.string.media_video_badge),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f),
                        shape = MaterialTheme.shapes.extraSmall,
                    )
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
private fun MediaDetail(
    item: MediaItem,
    credentials: AccountCredentials,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Text(
                text = item.day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AuthenticatedImage(
            url = item.detailPreviewUrl,
            credentials = credentials,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentScale = ContentScale.Fit,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.detail_file_id, item.fileId))
                Text(stringResource(R.string.detail_mime, item.mimeType ?: stringResource(R.string.value_unknown)))
                Text(
                    stringResource(
                        R.string.detail_size,
                        item.width?.toString() ?: stringResource(R.string.value_unknown_short),
                        item.height?.toString() ?: stringResource(R.string.value_unknown_short),
                    ),
                )
                item.videoDurationSeconds?.let {
                    Text(stringResource(R.string.detail_duration_seconds, it))
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedImage(
    url: String,
    credentials: AccountCredentials,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val headers = NetworkHeaders.Builder()
        .set("Authorization", OkHttpCredentials.basic(credentials.loginName, credentials.appPassword))
        .set("X-Requested-With", "XMLHttpRequest")
        .set("OCS-APIRequest", "true")
        .build()
    val request = ImageRequest.Builder(context)
        .data(url)
        .httpHeaders(headers)
        .build()

    AsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
    )
}

@Composable
private fun StatusBlock(state: MainUiState) {
    val message = state.errorMessage ?: state.statusMessage ?: return
    val color = if (state.errorMessage != null) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Text(
        text = message.asString(),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        color = color,
        style = MaterialTheme.typography.bodyMedium,
    )
}
