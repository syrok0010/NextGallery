package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.syrok0010.nextgallery.feature.albums.AlbumOrigin
import com.syrok0010.nextgallery.feature.albums.AlbumsPanel
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import com.syrok0010.nextgallery.router.AppNavigator
import com.syrok0010.nextgallery.router.NextGalleryRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

@Composable
internal fun AlbumsScreen(
    navigator: AppNavigator = koinInject(),
    viewModel: AlbumsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf<AlbumOrigin?>(null) }
    LibraryScreenScaffold(TopLevelDestination.Albums, onRefresh = viewModel::refresh) {
        AlbumsPanel(state, listState, filter, {
            filter = it
        }, viewModel::refresh, { album ->
            album.location?.let {
                navigator.open(NextGalleryRoute.Album(it, album.name))
            }
        })
    }
}
