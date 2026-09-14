package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.syrok0010.nextgallery.feature.albums.AlbumContentsState
import com.syrok0010.nextgallery.feature.albums.AlbumContentsStatus
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
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
    val slots = remember(contents.items) { contents.items.toMediaSlots() }
    DisposableEffect(album.location, viewModel) {
        viewModel.select(album.location)
        onDispose { viewModel.select(null) }
    }
    MediaCollectionScreen(
        slots = slots,
        accessAllowed = !contents.permissionRequired,
        emptyContent = { AlbumContentsStatus(contents, viewModel::refresh) },
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            album,
            onLogout,
            viewerVisible = viewerVisible,
            contents = contents,
            onRefreshContents = viewModel::refresh,
            onBack = onBack,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (contents.items.isNotEmpty() && (contents.loading || contents.failed)) {
                    AlbumContentsStatus(contents, viewModel::refresh)
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
