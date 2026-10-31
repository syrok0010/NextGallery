package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionState
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.albumAdditionMessage
import com.syrok0010.nextgallery.feature.collection.MediaTileActivity
import com.syrok0010.nextgallery.feature.collection.MediaTileOperation
import com.syrok0010.nextgallery.feature.collection.icon
import com.syrok0010.nextgallery.feature.deletion.DeletionProgress
import com.syrok0010.nextgallery.feature.deletion.DeletionResult
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.deletion.mediaDeletionMessage
import com.syrok0010.nextgallery.feature.upload.UploadActivity
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.UploadResult
import kotlinx.coroutines.delay
import org.koin.compose.koinInject

/** A running operation or a result the user has not seen yet. */
internal data class OperationEntry(
    val operation: MediaTileOperation,
    val title: String,
    val details: List<String> = emptyList(),
    val running: Boolean = false,
    /** In 0..1 while running; null when the operation cannot tell. */
    val progress: Float? = null,
    val failed: Boolean = false,
)

/**
 * Operations of the whole app, shown in the library header and on the tiles they change. The
 * modules keep the state, so it outlives the screen that started an operation.
 */
internal class OperationStatus(
    private val uploads: UploadQueue,
    private val additions: AlbumAdditions,
    private val deletions: MediaDeletionRepository,
    private val upload: State<UploadActivity?>,
    private val uploadResult: State<UploadResult?>,
    private val addition: State<AlbumAdditionState>,
    private val deletion: State<DeletionProgress?>,
    private val deletionResult: State<DeletionResult?>,
) {
    /** Queued uploads count too; terminal results never block logout. */
    val running: Boolean
        get() = operationsRunning(upload.value, addition.value, deletion.value)

    /** Re-read the sources: a write may have started before the menu could recompose. */
    fun tryLogout(onLogout: () -> Unit): Boolean {
        if (operationsRunning(uploads.activity.value, additions.state.value, deletions.progress.value)) {
            return false
        }
        onLogout()
        return true
    }

    /** Reads snapshot state: a tile recomposes only when its own activity changes. */
    fun tileActivity(item: MediaItem): MediaTileActivity? {
        val fileId = item.remoteFileId
        if (fileId != null && deletion.value?.fileIds?.contains(fileId) == true) {
            return MediaTileActivity(MediaTileOperation.Delete)
        }
        val contentUri = item.localCopy?.contentUri
        val upload = upload.value
        if (contentUri != null && upload != null) {
            if (contentUri == upload.current) {
                return MediaTileActivity(MediaTileOperation.Upload, upload.percent?.div(100f))
            }
            if (contentUri in upload.pending) {
                return MediaTileActivity(MediaTileOperation.Upload, 0f)
            }
        }
        val addition = addition.value
        if (fileId != null && addition is AlbumAdditionState.Running && fileId in addition.fileIds) {
            return MediaTileActivity(MediaTileOperation.AddToAlbum)
        }
        return null
    }

    @Composable
    fun entries(): List<OperationEntry> =
        listOfNotNull(
            upload.value?.let { uploadEntry(it) },
            uploadResult.value?.let { uploadResultEntry(it) },
            additionEntry(addition.value),
            deletion.value?.let { deletionEntry(it) },
            deletionResult.value?.let { deletionResultEntry(it) },
        )

    /** Successful results go away on their own; failures wait until the list is opened. */
    fun consumeSucceeded() {
        if (uploadResult.value?.failed == false) uploads.consumeResult()
        if ((addition.value as? AlbumAdditionState.Done)?.result?.failed == 0) additions.consume()
        if (deletionResult.value?.failed == false) deletions.consume()
    }

    /** Anything running or a result not shown yet. */
    val active: Boolean
        get() = upload.value != null ||
            uploadResult.value != null ||
            addition.value != AlbumAdditionState.Idle ||
            deletion.value != null ||
            deletionResult.value?.let { mediaDeletionMessage(it).isNotEmpty() } == true

    fun consumeAll() {
        uploads.consumeResult()
        additions.consume()
        deletions.consume()
    }
}

private fun operationsRunning(
    upload: UploadActivity?,
    addition: AlbumAdditionState,
    deletion: DeletionProgress?,
): Boolean = upload != null || addition is AlbumAdditionState.Running || deletion != null

@Composable
internal fun rememberOperationStatus(
    uploads: UploadQueue = koinInject(),
    additions: AlbumAdditions = koinInject(),
    deletions: MediaDeletionRepository = koinInject(),
): OperationStatus {
    val upload = uploads.activity.collectAsStateWithLifecycle()
    val uploadResult = uploads.result.collectAsStateWithLifecycle()
    val addition = additions.state.collectAsStateWithLifecycle()
    val deletion = deletions.progress.collectAsStateWithLifecycle()
    val deletionResult = deletions.result.collectAsStateWithLifecycle()
    return remember(uploads, additions, deletions) {
        OperationStatus(
            uploads,
            additions,
            deletions,
            upload,
            uploadResult,
            addition,
            deletion,
            deletionResult,
        )
    }
}

private val UploadResult.failed: Boolean get() = failures.isNotEmpty() || uploaded < files

@Composable
private fun uploadEntry(activity: UploadActivity) =
    OperationEntry(
        operation = MediaTileOperation.Upload,
        title = stringResource(
            R.string.upload_progress,
            (activity.processed + 1).coerceAtMost(activity.total),
            activity.total,
        ),
        running = true,
        progress = (activity.processed + (activity.percent ?: 0) / 100f) / activity.total,
    )

@Composable
private fun uploadResultEntry(result: UploadResult) =
    OperationEntry(
        operation = MediaTileOperation.Upload,
        title = if (result.failures.isEmpty()) {
            stringResource(R.string.upload_done, result.uploaded, result.files)
        } else {
            stringResource(R.string.upload_stopped, result.failures.joinToString(", "))
        },
        details = buildList {
            if (result.failures.isNotEmpty()) {
                add(stringResource(R.string.upload_count, result.uploaded, result.files))
            }
            result.details.mapTo(this) { it.asString() }
        },
        failed = result.failed,
    )

@Composable
private fun additionEntry(state: AlbumAdditionState): OperationEntry? {
    val lines = albumAdditionMessage(state)?.map { it.asString() } ?: return null
    return OperationEntry(
        operation = MediaTileOperation.AddToAlbum,
        title = lines.first(),
        details = lines.drop(1),
        running = state is AlbumAdditionState.Running,
        failed = state is AlbumAdditionState.Failed ||
            (state is AlbumAdditionState.Done && state.result.failed > 0),
    )
}

@Composable
private fun deletionEntry(progress: DeletionProgress) =
    OperationEntry(
        operation = MediaTileOperation.Delete,
        title = stringResource(R.string.delete_progress, progress.processed, progress.total),
        running = true,
        progress = progress.processed.toFloat() / progress.total,
    )

@Composable
private fun deletionResultEntry(result: DeletionResult): OperationEntry? {
    val lines = mediaDeletionMessage(result).map { it.asString() }.ifEmpty { return null }
    return OperationEntry(
        operation = MediaTileOperation.Delete,
        title = stringResource(
            if (result.failed) R.string.delete_done_with_errors else R.string.delete_done,
        ),
        details = lines,
        failed = result.failed,
    )
}

/**
 * A pill in the header: what is running, or the result once everything is done. Tapping it opens
 * the list of operations right below it. Successful results go away after a few seconds on a
 * visible screen; failures stay until the list has been opened.
 */
@Composable
internal fun OperationStatusPill(status: OperationStatus, visible: Boolean, modifier: Modifier = Modifier) {
    val entries = status.entries()
    var expanded by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val finished = entries.filterNot { it.running }
    LaunchedEffect(finished, visible, expanded) {
        if (!visible || expanded || finished.none { !it.failed }) return@LaunchedEffect
        // A result timing out while the app is stopped would go unseen; the delay restarts.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            delay(RESULT_MILLIS)
            status.consumeSucceeded()
        }
    }
    // Logging out clears everything while the list may be open.
    if (entries.isEmpty()) expanded = false
    // Keeps the last entries while the header fades back to the app name.
    val shown = remember { mutableStateOf(entries) }
    if (entries.isNotEmpty()) shown.value = entries
    // The list takes the width of the pill, so it opens centered right under it.
    var pillWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .onSizeChanged { pillWidth = with(density) { it.width.toDp() } }
                .testTag("operation_status"),
        ) {
            PillContent(shown.value)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = {
                expanded = false
                status.consumeAll()
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.width(pillWidth),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                entries.forEach { OperationRow(it) }
            }
        }
    }
}

@Composable
private fun PillContent(entries: List<OperationEntry>) {
    val running = entries.filter { it.running }
    val failed = running.isEmpty() && entries.any { it.failed }
    Row(
        Modifier.padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            running.isNotEmpty() -> {
                val progress = running.singleOrNull()?.progress
                val ring = Modifier.size(16.dp)
                if (progress == null) {
                    CircularProgressIndicator(ring, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(
                        progress = { progress },
                        modifier = ring,
                        strokeWidth = 2.dp,
                        gapSize = 0.dp,
                    )
                }
            }

            failed -> Icon(
                Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.error,
            )

            else -> Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                // A running operation matters more than results waiting next to it.
                running.size == 1 -> running.single().title
                running.isNotEmpty() ->
                    pluralStringResource(R.plurals.operations_running, running.size, running.size)
                entries.size == 1 -> entries.single().title
                else -> pluralStringResource(R.plurals.operations_done, entries.size, entries.size)
            },
            style = MaterialTheme.typography.labelLarge,
            color = if (failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OperationRow(entry: OperationEntry) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (entry.failed) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
            modifier = Modifier.size(32.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(entry.operation.icon(), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (entry.failed) MaterialTheme.colorScheme.error else Color.Unspecified,
            )
            entry.details.forEach {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.running) {
                val bar = Modifier.fillMaxWidth().padding(top = 4.dp)
                if (entry.progress == null) {
                    LinearProgressIndicator(bar)
                } else {
                    LinearProgressIndicator(progress = { entry.progress }, modifier = bar)
                }
            }
        }
    }
}

private const val RESULT_MILLIS = 6_000L
