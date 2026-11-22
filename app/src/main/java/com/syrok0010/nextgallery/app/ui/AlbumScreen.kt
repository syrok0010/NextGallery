package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.feature.albums.AlbumContentsStatus
import com.syrok0010.nextgallery.feature.albums.AlbumContentsViewModel
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import com.syrok0010.nextgallery.router.NextGalleryRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
internal fun AlbumScreen(
    album: NextGalleryRoute.Album,
    viewModel: AlbumContentsViewModel = koinViewModel { parametersOf(album.location) },
) {
    val contents by viewModel.state.collectAsStateWithLifecycle()
    val slots = remember(contents.items) { contents.items.toMediaSlots() }
    val operations = rememberOperationStatus()
    val tileActivity = remember(operations) { operations::tileActivity }
    MediaCollectionScreen(
        slots = slots,
        emptyContent = { AlbumContentsStatus(contents, viewModel::refresh) },
        tileActivity = tileActivity,
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Albums,
            viewerVisible = viewerVisible,
            onRefresh = viewModel::refresh,
            isSubscreen = true,
            title = album.title,
            operations = operations,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (contents.items.isNotEmpty() &&
                    (contents.loading || contents.failed || contents.pendingMetadata > 0)
                ) {
                    AlbumContentsStatus(contents, viewModel::refresh)
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
