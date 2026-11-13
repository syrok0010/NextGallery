package com.syrok0010.nextgallery.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
import org.koin.compose.getKoin

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

    /** Any in-flight operation, including queued work, blocks logout. */
    val running: Boolean
        get() = currentSnapshots().any { it.running }

    /** Re-read the aggregated snapshots immediately before logout. */
    fun tryLogout(onLogout: () -> Unit): Boolean {
        if (running) return false
        onLogout()
        return true
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
    // Classic DSL collects bound sources only via getAll(); koinInject() needs a List definition.
    sources: List<OperationStatusSource> = getKoin().getAll(),
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
    onOpenUploads: () -> Unit,
    modifier: Modifier = Modifier,
    /** The viewer keeps the pill as wide as its text, so it covers less of the photo. */
    fillWidth: Boolean = true,
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
    // Kept composed while the list fades out after closing.
    val listVisibility = remember { MutableTransitionState(false) }
    listVisibility.targetState = expanded
    Box(modifier) {
        Surface(
            onClick = { expanded = true },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .height(36.dp)
                .testTag("operation_status"),
        ) {
            PillContent(shown.value)
        }
        if (listVisibility.currentState || listVisibility.targetState) {
            Popup(
                popupPositionProvider = StatusListPosition,
                onDismissRequest = {
                    expanded = false
                    status.consumeAll()
                },
                properties = PopupProperties(focusable = true),
            ) {
                AnimatedVisibility(
                    visibleState = listVisibility,
                    enter = fadeIn() + expandVertically(expandFrom = Alignment.Top),
                    exit = fadeOut(),
                ) {
                    StatusList(entries) {
                        expanded = false
                        onOpenUploads()
                    }
                }
            }
        }
    }
}

/** Centered on the screen right below the pill, which may be off-center or narrow. */
private object StatusListPosition : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset = IntOffset((windowSize.width - popupContentSize.width) / 2, anchorBounds.bottom)
}

/** As wide as a phone screen allows, but not stretched across a tablet. */
@Composable
private fun StatusList(entries: List<OperationEntry>, onOpenUploads: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 3.dp,
        modifier = Modifier
            .padding(8.dp)
            .widthIn(max = 480.dp)
            .fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            entries.forEach { entry -> OperationRow(entry) }
            if (entries.any { it.operation == MediaTileOperation.Upload }) {
                FilledTonalButton(onClick = onOpenUploads, modifier = Modifier.fillMaxWidth()) {
                    Icon(
                        painterResource(R.drawable.ic_cloud_upload),
                        contentDescription = null,
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                    )
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(stringResource(R.string.uploads_open))
                }
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
