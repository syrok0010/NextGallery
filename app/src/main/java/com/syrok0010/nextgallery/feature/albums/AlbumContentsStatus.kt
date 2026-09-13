package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R

@Composable
internal fun AlbumContentsStatus(
    state: AlbumContentsState,
    onRetry: () -> Unit,
) {
    Column(Modifier.testTag("album_contents")) {
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
    }
}
