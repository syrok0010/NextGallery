package com.syrok0010.nextgallery.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.common.AuthenticatedImage
import java.time.format.DateTimeFormatter

@Composable
internal fun MissingMediaDetailScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.detail_missing_item),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onBack) {
            Text(stringResource(R.string.action_back))
        }
    }
}

@Composable
internal fun MediaDetailScreen(
    item: MediaItem,
    credentials: AccountCredentials,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.action_back))
            }
            Text(
                text = item.day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        AuthenticatedImage(
            url = item.detailPreviewUrl,
            credentials = credentials,
            contentDescription = item.displayName,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            contentScale = ContentScale.Fit,
        )

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(item.displayName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.detail_file_id, item.fileId))
                Text(stringResource(R.string.detail_mime, item.mimeType ?: stringResource(R.string.value_unknown)))
                Text(
                    stringResource(
                        R.string.detail_size,
                        item.width?.toString() ?: stringResource(R.string.value_unknown_short),
                        item.height?.toString() ?: stringResource(R.string.value_unknown_short),
                    ),
                )
                item.videoDurationSeconds?.let {
                    Text(stringResource(R.string.detail_duration_seconds, it))
                }
            }
        }
    }
}
