package com.syrok0010.nextgallery.app.ui

import android.content.ContentResolver
import android.provider.OpenableColumns
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.upload.UploadItem
import com.syrok0010.nextgallery.feature.upload.UploadItemState
import com.syrok0010.nextgallery.feature.upload.UploadPhase
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

internal val LocalOpenUploads = staticCompositionLocalOf<() -> Unit> { {} }

private class UploadEntry(val item: UploadItem, val state: UploadItemState)

/** Files grouped by status rather than by batch: batches are how files were sent, not what the user tracks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadsScreen(onBack: () -> Unit, queue: UploadQueue = koinInject()) {
    val items by queue.items.collectAsStateWithLifecycle()
    val states by remember(items) {
        if (items.isEmpty()) {
            flowOf(
                emptyList(),
            )
        } else {
            combine(items.map { it.state }) { it.toList() }
        }
    }.collectAsStateWithLifecycle(items.map { it.state.value })
    val entries = items.zip(states, ::UploadEntry)
    val uploading = entries.filter { it.state.phase == UploadPhase.Uploading }
    val waiting = entries.filter { it.state.phase == UploadPhase.Waiting }
    // Files that need attention come before ordinary outcomes.
    val done = entries.filter { it.state.phase.terminal }.sortedBy { !it.state.phase.problem }
    val sizes = rememberFileSizes(items)
    val storageError by queue.storageError.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.uploads_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.uploads_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty() && !storageError) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    stringResource(R.string.uploads_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (storageError) {
                item(key = "storage-error") {
                    Text(
                        stringResource(R.string.uploads_storage_error),
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            if (uploading.isNotEmpty()) {
                item(
                    key = "uploading",
                ) { SectionHeader(stringResource(R.string.uploads_section_uploading)) }
                items(
                    uploading,
                    key = { it.item.id },
                ) { UploadingRow(it, sizes[it.item.selection.contentUri]) { queue.cancel(it.item) } }
            }
            if (waiting.isNotEmpty()) {
                item(key = "waiting") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_waiting, waiting, sizes),
                        stringResource(R.string.uploads_cancel_all),
                        queue::cancelAll,
                    )
                }
                items(
                    waiting,
                    key = { it.item.id },
                ) { UploadRow(it, sizes[it.item.selection.contentUri], { queue.cancel(it.item) }, { queue.retryActions(it.item) }) }
            }
            if (done.isNotEmpty()) {
                item(key = "done") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_done, done, sizes),
                        stringResource(R.string.uploads_clear),
                        queue::clearFinished,
                    )
                }
                items(
                    done,
                    key = { it.item.id },
                ) { UploadRow(it, sizes[it.item.selection.contentUri], { queue.cancel(it.item) }, { queue.retryActions(it.item) }) }
            }
        }
    }
}

private val UploadPhase.problem: Boolean
    get() = this == UploadPhase.Failed || this == UploadPhase.NeedsAttention || this == UploadPhase.NameConflict

/** The total size is shown only when every file's size is known; a partial sum would mislead. */
@Composable
private fun sectionTitle(title: Int, entries: List<UploadEntry>, sizes: Map<String, Long>): String {
    val known = entries.mapNotNull { sizes[it.item.selection.contentUri] }
    return if (known.size == entries.size) {
        stringResource(
            R.string.uploads_section_count_size,
            stringResource(title),
            entries.size,
            fileSize(known.sum()),
        )
    } else {
        stringResource(R.string.uploads_section_count, stringResource(title), entries.size)
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(
                min = 40.dp,
            ).padding(start = 16.dp, end = 8.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            Modifier.weight(1f),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun UploadingRow(entry: UploadEntry, size: Long?, onCancel: () -> Unit) {
    val percent = entry.state.percent
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry.item, 36)
        Column(Modifier.weight(1f)) {
            FileName(entry.item)
            Row(
                Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bar = Modifier.weight(1f)
                if (percent == null) {
                    LinearProgressIndicator(bar)
                } else {
                    LinearProgressIndicator({
                        percent / 100f
                    }, bar)
                }
                val sent = when {
                    percent != null && size != null ->
                        stringResource(
                            R.string.uploads_sent,
                            fileSize(size * percent / 100),
                            fileSize(size),
                        )

                    percent != null -> stringResource(R.string.uploads_percent, percent)

                    else -> size?.let { fileSize(it) }
                }
                if (sent != null) SizeText(sent)
            }
        }
        CancelButton(onCancel)
    }
}

@Composable
private fun UploadRow(entry: UploadEntry, size: Long?, onCancel: () -> Unit, onRetry: () -> Unit) {
    val state = entry.state
    val phase = state.phase
    val details = uploadDetails(entry)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 4.dp)
            .alpha(if (phase == UploadPhase.Cancelled) 0.55f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Thumbnail(entry.item, 32)
        Column(Modifier.weight(1f).padding(vertical = if (details.isEmpty()) 0.dp else 4.dp)) {
            FileName(entry.item)
            details.forEach { (text, error) ->
                Text(
                    text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state.retryActions) {
                TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text(stringResource(R.string.uploads_retry_actions))
                }
            }
        }
        size?.let { SizeText(fileSize(it)) }
        // A file waiting for its follow-up or for attention can still be cancelled after upload.
        if (!phase.terminal || phase == UploadPhase.NeedsAttention || state.pendingAction) {
            CancelButton(onCancel)
        } else {
            StatusIcon(state)
        }
    }
}

/** Lines under the file name: the outcome when it needs explaining, then follow-up steps; `true` marks errors. */
@Composable
private fun uploadDetails(entry: UploadEntry): List<Pair<String, Boolean>> {
    val state = entry.state
    val name = entry.item.selection.displayName
    return buildList {
        when (state.phase) {
            UploadPhase.Failed -> add(stringResource(R.string.uploads_failed, state.failure.orEmpty()) to true)
            UploadPhase.NameConflict -> add(stringResource(R.string.uploads_conflict) to true)
            UploadPhase.NeedsAttention -> add(
                when (state.failure) {
                    "Source" -> stringResource(R.string.uploads_source_attention)
                    "Foreground" -> stringResource(R.string.upload_error_foreground)
                    "Format" -> stringResource(R.string.uploads_format_attention)
                    "Directory" -> stringResource(R.string.upload_error_directory, state.httpStatus ?: 0, name)
                    "Http" -> stringResource(R.string.upload_error_http, state.httpStatus ?: 0, name)
                    else -> stringResource(R.string.uploads_attention)
                } to true,
            )
            else -> Unit
        }
        if (state.actionSuppressed) add(stringResource(R.string.uploads_action_suppressed) to false)
        state.albumAdded?.let { add(stringResource(R.string.album_upload_summary, it, 1) to false) }
        if (state.pendingAction) {
            val text = if (state.attention) R.string.uploads_action_attention else R.string.uploads_action_pending
            add(stringResource(text) to true)
        }
        state.linkProblem?.let {
            add(stringResource(if (it == "Format") R.string.uploads_format_attention else R.string.uploads_link_attention) to true)
        }
        state.albumProblem?.let { problem ->
            val text = when (problem) {
                "Format" -> R.string.uploads_format_attention
                "TargetChanged" -> R.string.uploads_album_changed
                "Owner" -> R.string.uploads_album_source
                "LegacyTarget" -> R.string.uploads_album_legacy
                "ReplayUnsupported" -> R.string.uploads_album_replay
                "Uncertain" -> R.string.uploads_album_uncertain
                else -> R.string.uploads_album_attention
            }
            add(stringResource(text) to true)
        }
    }
}

@Composable
private fun StatusIcon(state: UploadItemState) {
    val colors = MaterialTheme.colorScheme
    val (painter: Painter, tint: Color, description: String) = when (state.phase) {
        UploadPhase.Uploaded ->
            Triple(
                painterResource(R.drawable.ic_check),
                colors.primary,
                stringResource(R.string.uploads_uploaded),
            )

        UploadPhase.Existing ->
            Triple(
                painterResource(R.drawable.ic_cloud),
                colors.onSurfaceVariant,
                stringResource(R.string.uploads_existing),
            )

        UploadPhase.NameConflict ->
            Triple(
                rememberVectorPainter(Icons.Filled.Warning),
                colors.error,
                stringResource(R.string.uploads_conflict),
            )

        UploadPhase.Failed, UploadPhase.NeedsAttention -> Triple(
            painterResource(R.drawable.ic_error),
            colors.error,
            stringResource(R.string.uploads_failed, state.failure.orEmpty()),
        )

        else -> Triple(
            painterResource(R.drawable.ic_block),
            colors.outline,
            stringResource(R.string.uploads_cancelled),
        )
    }
    // Same width as the cancel button, so sizes stay aligned between sections.
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(painter, description, Modifier.size(18.dp), tint = tint)
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    IconButton(onClick = onCancel, modifier = Modifier.size(40.dp)) {
        Icon(
            Icons.Filled.Close,
            stringResource(R.string.uploads_cancel_file),
            Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Thumbnail(item: UploadItem, size: Int) {
    AsyncImage(
        model = item.selection.contentUri,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape(6.dp)),
    )
}

@Composable
private fun FileName(item: UploadItem) {
    Text(
        item.selection.displayName,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun SizeText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun fileSize(bytes: Long): String =
    Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Sizes come from MediaStore off the main thread; a file it no longer knows simply has no size. */
@Composable
private fun rememberFileSizes(items: List<UploadItem>): Map<String, Long> {
    val resolver = LocalContext.current.contentResolver
    val sizes = remember { mutableStateMapOf<String, Long>() }
    LaunchedEffect(items) {
        val missing = items.map { it.selection.contentUri }.distinct().filterNot(sizes::containsKey)
        if (missing.isEmpty()) return@LaunchedEffect
        val found = withContext(Dispatchers.IO) {
            missing.mapNotNull { uri -> resolver.size(uri)?.let { uri to it } }
        }
        sizes.putAll(found)
    }
    return sizes
}

private fun ContentResolver.size(uri: String): Long? =
    runCatching {
        query(uri.toUri(), arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    }.getOrNull()
