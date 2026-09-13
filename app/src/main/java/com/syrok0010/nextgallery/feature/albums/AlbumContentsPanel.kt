package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose

@Composable
internal fun AlbumContentsPanel(
    state: AlbumContentsState,
    gridState: LazyGridState,
    onRetry: () -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    Column(Modifier.fillMaxSize().testTag("album_contents")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.album_contents_progress, state.loaded, state.total), style = MaterialTheme.typography.bodySmall)
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (state.permissionRequired) Text(stringResource(R.string.albums_permission))
            if (state.failed) {
                Text(stringResource(R.string.album_contents_error), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, contentPadding = PaddingValues(0.dp)) { Text(stringResource(R.string.action_refresh)) }
            }
            if (!state.loading && !state.permissionRequired && !state.failed && state.items.isEmpty())
                Text(stringResource(R.string.album_contents_empty))
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(4), state = gridState,
            horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
            contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.weight(1f).testTag("album_contents_grid"),
        ) {
            items(state.items, key = { it.mediaId.value }) { item ->
                Box(Modifier.aspectRatio(1f).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .testTag("album_media:${item.mediaId.value}").clickable { onSelect(item) }) {
                    MediaAssetImage(item, MediaImagePurpose.TimelineThumbnail, item.displayName, Modifier.fillMaxSize())
                    if (item.isVideo) Icon(Icons.Default.PlayArrow, stringResource(R.string.album_video),
                        Modifier.align(Alignment.BottomEnd).padding(4.dp))
                }
            }
        }
    }
}
