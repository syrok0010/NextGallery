package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.upload.UploadItem
import com.syrok0010.nextgallery.feature.upload.UploadPhase
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import org.koin.compose.koinInject

internal val LocalOpenUploads = staticCompositionLocalOf<() -> Unit> { {} }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadsScreen(onBack: () -> Unit, queue: UploadQueue = koinInject()) {
    val batches by queue.batches.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.uploads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.uploads_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = queue::cancelAll) { Text(stringResource(R.string.uploads_cancel_all)) }
                    TextButton(onClick = queue::clearFinished) { Text(stringResource(R.string.uploads_clear)) }
                }
            }
            if (batches.isEmpty()) item { Text(stringResource(R.string.uploads_empty)) }
            batches.forEachIndexed { index, batch ->
                item(key = "batch-${batch.id}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(R.string.uploads_batch, index + 1), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = batch::cancel) { Text(stringResource(R.string.uploads_cancel_batch)) }
                    }
                }
                items(batch.items, key = { "${batch.id}-${batch.items.indexOf(it)}" }) { item -> UploadRow(item) }
            }
        }
    }
}

@Composable
private fun UploadRow(item: UploadItem) {
    val state by item.state.collectAsStateWithLifecycle()
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AsyncImage(model = item.selection.contentUri, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.selection.displayName, style = MaterialTheme.typography.bodyLarge)
                Text(
                    when (state.phase) {
                        UploadPhase.Waiting -> stringResource(R.string.uploads_waiting)
                        UploadPhase.Uploading -> state.percent?.let { stringResource(R.string.uploads_percent, it) } ?: stringResource(R.string.uploads_uploading)
                        UploadPhase.Uploaded -> stringResource(R.string.uploads_uploaded)
                        UploadPhase.Existing -> stringResource(R.string.uploads_existing)
                        UploadPhase.NameConflict -> stringResource(R.string.uploads_conflict)
                        UploadPhase.Failed -> stringResource(R.string.uploads_failed, state.failure.orEmpty())
                        UploadPhase.Cancelled -> stringResource(R.string.uploads_cancelled)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.phase == UploadPhase.Failed || state.phase == UploadPhase.NameConflict) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.phase == UploadPhase.Uploading) {
                    state.percent?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()) }
                        ?: LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            if (!state.phase.terminal) TextButton(onClick = item::cancel) { Text(stringResource(R.string.uploads_cancel_file)) }
        }
    }
}
