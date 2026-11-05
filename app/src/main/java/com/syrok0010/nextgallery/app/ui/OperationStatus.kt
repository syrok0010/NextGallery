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
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.collection.MediaTileActivity
import com.syrok0010.nextgallery.feature.collection.MediaTileOperation
import com.syrok0010.nextgallery.feature.collection.icon
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
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
    private val sources: List<OperationStatusSource>,
    private val snapshots: State<List<OperationStatusSnapshot>>,
) {
    /** Reads snapshot state: a tile recomposes only when its own activity changes. */
    fun tileActivity(item: MediaItem): MediaTileActivity? {
        val activities = currentSnapshots()
            .sortedBy { it.kind.priority }
            .flatMap { snapshot ->
                snapshot.media.map { snapshot.kind.toTileOperation() to it }
            }
        val localUri = item.localCopy?.contentUri
        val remoteFileId = item.remoteFileId
        activities
            .firstOrNull { (_, activity) ->
                when (val reference = activity.reference) {
                    is OperationMediaReference.LocalUri -> reference.value == localUri
                    is OperationMediaReference.RemoteFileId -> reference.value == remoteFileId
                }
            }?.let { (operation, activity) ->
                return MediaTileActivity(operation, activity.progress)
            }
        return null
    }

    @Composable
    fun entries(): List<OperationEntry> =
        currentSnapshots().map { snapshot ->
            OperationEntry(
                operation = snapshot.kind.toTileOperation(),
                title = snapshot.title.asString(),
                details = snapshot.details.map { it.asString() },
                running = snapshot.running,
                progress = snapshot.progress,
                failed = snapshot.failed,
            )
        }

    /** Successful results go away on their own; failures wait until the list is opened. */
    fun consumeSucceeded() {
        sources.forEach(OperationStatusSource::consumeSucceeded)
    }

    /** Anything running or a result not shown yet. */
    val active: Boolean
        get() = currentSnapshots().isNotEmpty()

    fun consumeAll() {
        sources.forEach(OperationStatusSource::consumeAll)
    }

    private fun currentSnapshots() = snapshots.value
}

private val OperationKind.priority: Int
    get() = when (this) {
        OperationKind.Delete -> 0
        OperationKind.Upload -> 1
        OperationKind.AddToAlbum -> 2
    }

private fun OperationKind.toTileOperation(): MediaTileOperation =
    when (this) {
        OperationKind.Upload -> MediaTileOperation.Upload
        OperationKind.AddToAlbum -> MediaTileOperation.AddToAlbum
        OperationKind.Delete -> MediaTileOperation.Delete
    }

@Composable
internal fun rememberOperationStatus(
    sources: List<OperationStatusSource> = koinInject(),
): OperationStatus {
    val snapshotFlow = remember(sources) {
        if (sources.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(sources.map(OperationStatusSource::operations)) { snapshots ->
                snapshots.flatMap { it }
            }
        }
    }
    val snapshots = snapshotFlow.collectAsStateWithLifecycle(emptyList())
    return remember(sources) { OperationStatus(sources, snapshots) }
}

/**
 * A pill in the header: what is running, or the result once everything is done. Tapping it opens
 * the list of operations right below it. Successful results go away after a few seconds on a
 * visible screen; failures stay until the list has been opened.
 */
@Composable
internal fun OperationStatusPill(
    status: OperationStatus,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
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
                Icon(
                    entry.operation.icon(),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
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
