package com.syrok0010.nextgallery.app.ui

import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateSetOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.images.LocalThumbnail
import com.syrok0010.nextgallery.feature.upload.UploadEntry
import com.syrok0010.nextgallery.feature.upload.UploadPhase
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/** Files grouped by status rather than by batch: batches are how files were sent, not what the user tracks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun UploadsScreen(onBack: () -> Unit, queue: UploadQueue = koinInject()) {
    val entries by queue.entries.collectAsStateWithLifecycle()
    val paused by queue.paused.collectAsStateWithLifecycle()
    val moves = rememberRowMoves(entries)
    val uploading = entries.filter { it.phase == UploadPhase.Uploading || it.id in moves.finishing }
    val waiting = entries.filter { it.phase == UploadPhase.Waiting }
    val rest = (entries - uploading.toSet() - waiting.toSet()).asReversed()
    // Unfinished work the user has to retry or cancel; «Clear» does not reach it, so it is not
    // mixed with the outcomes. A taken name is an outcome: nothing is left to do about it.
    val attention = rest.filter { it.problem != null }
    // The latest come first, so a file that has just moved here stays in sight.
    val done = rest - attention.toSet()

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
        // One key per file in every section: a finished upload moves to «Done» instead of reappearing.
        // Slow enough for the eye to follow the row across the list.
        val moveSpec = tween<IntOffset>(ROW_MOVE_MILLIS, easing = FastOutSlowInEasing)
        val row: @Composable LazyItemScope.(UploadEntry) -> Unit = { entry ->
            UploadRow(
                entry,
                { queue.cancel(entry.id) },
                { queue.retry(entry.id) },
                entry.id in moves.moving,
                Modifier.animateItem(placementSpec = moveSpec),
            )
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            if (attention.isNotEmpty()) {
                item(key = "attention") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_attention, attention),
                        Modifier.animateItem(placementSpec = moveSpec),
                    )
                }
                items(attention, key = { it.id }) { row(it) }
            }
            if (uploading.isNotEmpty()) {
                item(key = "uploading") {
                    SectionHeader(
                        stringResource(R.string.uploads_section_uploading),
                        Modifier.animateItem(placementSpec = moveSpec),
                    )
                }
                items(uploading, key = { it.id }) { row(it) }
            }
            if (waiting.isNotEmpty()) {
                item(key = "waiting") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_waiting, waiting),
                        Modifier.animateItem(placementSpec = moveSpec),
                        stringResource(R.string.uploads_cancel_all),
                        queue::cancelAll,
                        retry = queue::retryNow.takeIf { paused },
                    )
                }
                items(waiting, key = { it.id }) { row(it) }
            }
            if (done.isNotEmpty()) {
                item(key = "done") {
                    SectionHeader(
                        sectionTitle(R.string.uploads_section_done, done),
                        Modifier.animateItem(placementSpec = moveSpec),
                        stringResource(R.string.uploads_clear),
                        queue::clearFinished,
                    )
                }
                items(done, key = { it.id }) { row(it) }
            }
        }
    }
}

/**
 * [finishing]: files whose transfer has just ended. They stay a moment under «Uploading» in their
 * final state, so the user sees the outcome before the row moves on to «Done».
 * [moving]: files on their way to «Done», lifted above the list while they travel.
 */
private class RowMoves(val finishing: Set<String>, val moving: Set<String>)

@Composable
private fun rememberRowMoves(entries: List<UploadEntry>): RowMoves {
    val scope = rememberCoroutineScope()
    val finishing = remember { mutableStateSetOf<String>() }
    val moving = remember { mutableStateSetOf<String>() }
    var transferring by remember { mutableStateOf(emptySet<String>()) }
    val now = entries.filter { it.phase == UploadPhase.Uploading }.mapTo(HashSet()) { it.id }
    // A file sent back to waiting moves at once: it has no outcome to show.
    val ended = entries.filter {
        it.id in transferring && it.id !in now && it.phase != UploadPhase.Waiting
    }
    SideEffect {
        transferring = now
        ended.forEach { entry ->
            if (finishing.add(entry.id)) {
                scope.launch {
                    delay(FINISHED_PAUSE_MILLIS)
                    moving.add(entry.id)
                    finishing.remove(entry.id)
                    delay(ROW_MOVE_MILLIS.toLong())
                    moving.remove(entry.id)
                }
            }
        }
    }
    return RowMoves(finishing + ended.map { it.id }, moving)
}

private const val FINISHED_PAUSE_MILLIS = 800L
private const val ROW_MOVE_MILLIS = 600

@Composable
private fun sectionTitle(title: Int, entries: List<UploadEntry>): String =
    stringResource(
        R.string.uploads_section_count_size,
        stringResource(title),
        entries.size,
        fileSize(entries.sumOf { it.selection.local.sizeBytes }),
    )

@Composable
private fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: () -> Unit = {},
    retry: (() -> Unit)? = null,
) {
    Row(
        modifier
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
        if (retry != null) {
            IconButton(onClick = retry) {
                Icon(Icons.Default.Refresh, stringResource(R.string.uploads_retry_now))
            }
        }
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** The same row in every section, so a file keeps its place on screen while its state changes. */
@Composable
private fun UploadRow(
    entry: UploadEntry,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    lifted: Boolean,
    modifier: Modifier = Modifier,
) {
    // A moving row rises over its neighbours with its own background, so it does not blend in.
    val colors = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (lifted) colors.surfaceContainerHighest else colors.background,
    )
    val elevation by animateDpAsState(if (lifted) 6.dp else 0.dp)
    val phase = entry.phase
    val uploading = phase == UploadPhase.Uploading
    val problem = when {
        entry.problem != null -> stringResource(R.string.uploads_failed, entry.problem.asString())
        phase == UploadPhase.NameConflict -> stringResource(R.string.uploads_conflict)
        else -> null
    }
    val alpha by animateFloatAsState(if (phase == UploadPhase.Cancelled) 0.55f else 1f)
    Row(
        modifier
            .zIndex(if (lifted) 1f else 0f)
            .fillMaxWidth()
            .shadow(elevation)
            .background(background)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp)
            .alpha(alpha),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LocalThumbnail(
            entry.selection.local,
            Modifier.size(36.dp).clip(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            FileName(entry.selection)
            // The queue drops progress as the transfer ends, a moment before the journal moves
            // the file on: until then, and while the line folds away, it keeps the last progress.
            var transfer by remember { mutableStateOf(entry) }
            if (uploading && (entry.percent != null || transfer.percent == null)) transfer = entry
            AnimatedVisibility(uploading) { TransferProgress(transfer) }
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (entry.retryable) {
                TextButton(
                    onClick = onRetry,
                    contentPadding = PaddingValues(horizontal = 0.dp),
                ) { Text(stringResource(R.string.uploads_retry_actions)) }
            }
        }
        // While the file is on the wire its size is part of the progress line.
        AnimatedVisibility(!uploading) { SizeText(fileSize(entry.selection.local.sizeBytes)) }
        Crossfade(entry.cancellable) { cancellable ->
            if (cancellable) CancelButton(onCancel) else StatusIcon(entry)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransferProgress(entry: UploadEntry) {
    val percent = entry.percent
    val size = entry.selection.local.sizeBytes
    Row(
        // One height for both states, so the row does not jump when the bar gives way.
        Modifier.padding(top = 4.dp).height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (entry.saving) {
            // Every byte is sent; the server answers without progress.
            LoadingIndicator(Modifier.size(24.dp))
            SizeText(stringResource(R.string.uploads_saving))
        } else {
            val bar = Modifier.weight(1f)
            if (percent == null) {
                LinearProgressIndicator(bar)
            } else {
                LinearProgressIndicator({ percent / 100f }, bar)
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
}

@Composable
private fun StatusIcon(entry: UploadEntry) {
    val colors = MaterialTheme.colorScheme
    val (painter: Painter, tint: Color, description: String) = when (entry.phase) {
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

        UploadPhase.NeedsAttention -> Triple(
            painterResource(R.drawable.ic_error),
            colors.error,
            stringResource(R.string.uploads_failed, entry.problem?.asString().orEmpty()),
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
private fun FileName(selection: UploadSelection) {
    Text(
        selection.displayName,
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
