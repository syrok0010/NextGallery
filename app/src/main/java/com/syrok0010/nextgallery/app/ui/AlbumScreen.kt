package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.syrok0010.nextgallery.feature.albums.AlbumContentsStatus
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AlbumScreen(
    album: NextGalleryRoute.Album,
    onBack: () -> Unit,
    onLogout: () -> Unit,
    viewModel: AlbumContentsViewModel = koinViewModel { parametersOf(album.location) },
) {
    val contents by viewModel.state.collectAsState()
    val slots = remember(contents.items) { contents.items.toMediaSlots() }
    MediaCollectionScreen(
        slots = slots,
        emptyContent = { AlbumContentsStatus(contents, viewModel::refresh) },
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Albums,
            onLogout,
            viewerVisible = viewerVisible,
            onRefresh = viewModel::refresh,
            onBack = onBack,
            title = album.title,
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
