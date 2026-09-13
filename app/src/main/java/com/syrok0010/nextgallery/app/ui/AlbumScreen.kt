package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.albums.AlbumContentsPanel
import com.syrok0010.nextgallery.feature.albums.AlbumContentsState
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.viewer.MediaDetailScreen
import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun AlbumScreen(
    album: NextGalleryRoute.Album,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: AlbumContentsViewModel = koinViewModel(),
) {
    val loaded by viewModel.state.collectAsState()
    val contents = loaded.takeIf { it.location == album.location }
        ?: AlbumContentsState(location = album.location, loading = true)
    val gridState = rememberLazyGridState()
    var viewerKey by rememberSaveable(album.location) { mutableStateOf<String?>(null) }
    val sequence = remember(contents.items) {
        ViewerSequence(contents.items, contents.items.mapIndexed { index, item -> item.mediaId to index }.toMap())
    }
    val viewerId = viewerKey?.let(::MediaId)?.takeIf { it in sequence }
    val scope = rememberCoroutineScope()
    DisposableEffect(album.location, viewModel) {
        viewModel.select(album.location)
        onDispose { viewModel.select(null) }
    }
    LaunchedEffect(contents.permissionRequired) { if (contents.permissionRequired) viewerKey = null }
    Box(Modifier.fillMaxSize()) {
        LibraryScreenScaffold(album, onLogout, viewerVisible = viewerId != null, contents = contents,
            onRefreshContents = viewModel::refresh, onBack = onBack) {
            AlbumContentsPanel(contents, gridState, viewModel::refresh) { viewerKey = it.mediaId.value }
        }
        if (viewerId != null) MediaDetailScreen(
            initialMediaId = viewerId,
            sequence = sequence,
            tileBoundsForMediaId = { null },
            onBack = { item ->
                viewerKey = null
                val index = contents.items.indexOfFirst { it.mediaId == item.mediaId }
                if (index >= 0 && gridState.layoutInfo.visibleItemsInfo.none { it.index == index })
                    scope.launch { gridState.scrollToItem(index) }
            },
            onCurrentItemChange = { viewerKey = it.mediaId.value },
        )
    }
}
