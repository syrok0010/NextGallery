package com.syrok0010.nextgallery.feature.favorites

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem

@Composable
internal fun FavoriteViewerAction(item: MediaItem, favorites: CloudFavorites, enabled: Boolean) {
    val pending by favorites.pending.collectAsStateWithLifecycle()
    val changing = pending?.fileId == item.remoteFileId && item.remoteFileId != null
    val marked = if (changing) pending?.previous == true else item.isFavorite
    IconButton(enabled = enabled && item.remoteFileId != null && pending == null, onClick = { favorites.set(item) }) {
        if (changing) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
        else Icon(
            if (marked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            contentDescription = stringResource(when {
                item.remoteFileId == null -> R.string.favorite_cloud_only
                marked -> R.string.favorite_remove
                else -> R.string.favorite_add
            }),
            tint = if (item.remoteFileId == null) Color.White.copy(alpha = .4f) else Color.White,
        )
    }
}

@Composable
internal fun FavoriteItemNote(item: MediaItem) {
    if (item.remoteFileId == null) Text(
        stringResource(R.string.favorite_cloud_only),
        color = Color.White.copy(alpha = .8f),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
internal fun FavoriteError(favorites: CloudFavorites) {
    val failed by favorites.error.collectAsStateWithLifecycle()
    if (failed) AlertDialog(
        onDismissRequest = favorites::consumeError,
        title = { Text(stringResource(R.string.favorite_error_title)) },
        text = { Text(stringResource(R.string.favorite_error)) },
        confirmButton = { TextButton(onClick = favorites::consumeError) { Text(stringResource(R.string.favorite_error_close)) } },
    )
}
