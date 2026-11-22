package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.feature.albums.AlbumContentsStatus
import com.syrok0010.nextgallery.feature.albums.Albums
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import com.syrok0010.nextgallery.router.NextGalleryRoute
import org.koin.compose.koinInject

@Composable
internal fun AlbumScreen(album: NextGalleryRoute.Album, albums: Albums = koinInject()) {
    val contents by remember(album.location, albums) {
        albums.observeContents(album.location)
    }.collectAsStateWithLifecycle()
    val slots = remember(contents.items) { contents.items.toMediaSlots() }
    val operations = rememberOperationStatus()
    val tileActivity = remember(operations) { operations::tileActivity }
    MediaCollectionScreen(
        slots = slots,
        emptyContent = { AlbumContentsStatus(contents) { albums.refresh(album.location) } },
        tileActivity = tileActivity,
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Albums,
            viewerVisible = viewerVisible,
            onRefresh = { albums.refresh(album.location) },
            isSubscreen = true,
            title = album.title,
            operations = operations,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (contents.items.isNotEmpty() &&
                    (contents.loading || contents.failed || contents.pendingMetadata > 0)
                ) {
                    AlbumContentsStatus(contents) { albums.refresh(album.location) }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
}
