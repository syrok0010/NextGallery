package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.images.thumbnailRequest
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import org.koin.compose.koinInject

@Composable
internal fun AlbumsPanel(state: AlbumsUiState, listState: LazyListState, filter: AlbumOrigin?,
    onFilter: (AlbumOrigin?) -> Unit, onRetry: () -> Unit, onOpen: (AlbumSummary) -> Unit = {}) {
    val visible = remember(state.items, filter) { state.items.filter { filter == null || it.origin == filter } }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.fillMaxSize().testTag("album_catalog")) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(null, AlbumOrigin.Nextcloud, AlbumOrigin.Phone).forEach { origin ->
                    FilterChip(selected = filter == origin, onClick = { onFilter(origin) }, label = {
                        Text(stringResource(when (origin) { null -> R.string.albums_all; AlbumOrigin.Nextcloud -> R.string.albums_cloud; AlbumOrigin.Phone -> R.string.albums_phone }))
                    })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.remote.loading || state.local.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (!state.remote.supported && filter != AlbumOrigin.Phone) Text(stringResource(R.string.albums_unsupported))
                if (state.permission != LocalMediaPermissionMode.Full && filter != AlbumOrigin.Nextcloud)
                    Text(stringResource(R.string.albums_permission), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.remote.failed || state.local.failed) {
                    if (state.remote.failed) Text(stringResource(R.string.albums_remote_error), color = MaterialTheme.colorScheme.error)
                    if (state.local.failed) Text(stringResource(R.string.albums_local_error), color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.action_refresh)) }
                }
                if (visible.isEmpty() && !state.remote.loading && !state.local.loading && !state.remote.failed && !state.local.failed)
                    Text(stringResource(R.string.albums_empty))
            }
        }
        items(visible.chunked(2), key = { it.first().id }) { row ->
            AlbumCardRow(row, onOpen = onOpen)
        }
    }
}

@Composable
internal fun AlbumCardRow(albums: List<AlbumSummary>, onOpen: (AlbumSummary) -> Unit = {}, cover: @Composable (AlbumSummary) -> Unit = { AlbumCoverImage(it) }) {
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        albums.forEach { album ->
            Column(Modifier.weight(1f).fillMaxHeight().testTag("album_card:${album.id}").clickable(enabled = album.location != null) { onOpen(album) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.albums_cover_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    cover(album)
                    Surface(Modifier.align(Alignment.BottomStart).padding(8.dp), shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = .92f), contentColor = MaterialTheme.colorScheme.onSurface) {
                        Text(stringResource(if (album.origin == AlbumOrigin.Phone) R.string.albums_phone else R.string.albums_cloud),
                            style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp))
                    }
                }
                Text(album.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text(album.count?.let { stringResource(R.string.albums_count, it) } ?: stringResource(R.string.albums_count_unknown),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(album.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (albums.size == 1) Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun AlbumCoverImage(album: AlbumSummary, sessions: SessionStore = koinInject()) {
    val session by sessions.session.collectAsState()
    val context = LocalContext.current
    val request = remember(album.cover, session, context) {
        when (val cover = album.cover) {
            is AlbumCover.Remote -> (session as? SessionUiState.SignedIn)?.let {
                ImageRequest.Builder(context).data(thumbnailRequest(it.credentials, cover.fileId, cover.etag)).build()
            }
            is AlbumCover.Local -> ImageRequest.Builder(context).data(cover.uri)
                .memoryCacheKey("album:${cover.uri}:${cover.modified}").diskCacheKey("album:${cover.uri}:${cover.modified}").build()
            null -> null
        }
    }
    if (request != null) AsyncImage(request, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
}
