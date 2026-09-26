package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import com.syrok0010.nextgallery.feature.albums.AlbumOrigin
import com.syrok0010.nextgallery.feature.albums.AlbumSummary
import com.syrok0010.nextgallery.feature.albums.AlbumsPanel
import com.syrok0010.nextgallery.feature.albums.AlbumsViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun AlbumsScreen(
    onLogout: () -> Unit,
    onOpen: (AlbumSummary) -> Unit,
    viewModel: AlbumsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    var filter by rememberSaveable { mutableStateOf<AlbumOrigin?>(null) }
    LibraryScreenScaffold(TopLevelDestination.Albums, onLogout) {
        AlbumsPanel(state, listState, filter, { filter = it }, viewModel::refresh, onOpen)
    }
}
