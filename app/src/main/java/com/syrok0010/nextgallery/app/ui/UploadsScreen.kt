package com.syrok0010.nextgallery.app.ui

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.images.LocalThumbnail
import com.syrok0010.nextgallery.feature.upload.UploadItem
import com.syrok0010.nextgallery.feature.upload.UploadItemState
import com.syrok0010.nextgallery.feature.upload.UploadPhase
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import org.koin.compose.koinInject

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
        if (entries.isEmpty()) {
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
            if (uploading.isNotEmpty()) {
                item(
                    key = "uploading",
                ) { SectionHeader(stringResource(R.string.uploads_section_uploading)) }
                items(
                    uploading,
                    key = { it.item.id },
                ) { UploadingRow(it) { queue.cancel(it.item) } }
            }
            if (waiting.isNotEmpty()) {
                item(key = "waiting") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_waiting, waiting),
                        stringResource(R.string.uploads_cancel_all),
                        queue::cancelAll,
                    )
                }
                items(
                    waiting,
                    key = { it.item.id },
                ) { UploadRow(it) { queue.cancel(it.item) } }
            }
            if (done.isNotEmpty()) {
                item(key = "done") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_done, done),
                        stringResource(R.string.uploads_clear),
                        queue::clearFinished,
                    )
                }
                items(
                    done,
                    key = { it.item.id },
                ) { UploadRow(it) { queue.cancel(it.item) } }
            }
        }
    }
}

private val UploadPhase.problem: Boolean
    get() = this == UploadPhase.Failed || this == UploadPhase.NeedsAttention || this == UploadPhase.NameConflict

@Composable
private fun sectionTitle(title: Int, entries: List<UploadEntry>): String =
    stringResource(
        R.string.uploads_section_count_size,
        stringResource(title),
        entries.size,
        fileSize(entries.sumOf { it.item.selection.local.sizeBytes }),
    )

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
private fun UploadingRow(entry: UploadEntry, onCancel: () -> Unit) {
    val percent = entry.state.percent
    val size = entry.item.selection.local.sizeBytes
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalThumbnail(
            entry.item.selection.local,
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
        )
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
                SizeText(
                    if (percent == null) {
                        fileSize(size)
                    } else {
                        stringResource(
                            R.string.uploads_sent,
                            fileSize(size * percent / 100),
                            fileSize(size),
                        )
                    },
                )
            }
        }
        CancelButton(onCancel)
    }
}

@Composable
private fun UploadRow(entry: UploadEntry, onCancel: () -> Unit) {
    val phase = entry.state.phase
    val problem = when (phase) {
        UploadPhase.Failed, UploadPhase.NeedsAttention ->
            stringResource(R.string.uploads_failed, entry.state.failure.orEmpty())
        UploadPhase.NameConflict -> stringResource(R.string.uploads_conflict)
        else -> null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 4.dp)
            .alpha(if (phase == UploadPhase.Cancelled) 0.55f else 1f),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalThumbnail(
            entry.item.selection.local,
            Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            FileName(entry.item)
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        SizeText(fileSize(entry.item.selection.local.sizeBytes))
        if (phase.terminal) StatusIcon(entry.state) else CancelButton(onCancel)
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
