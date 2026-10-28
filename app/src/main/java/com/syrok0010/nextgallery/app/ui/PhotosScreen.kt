package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.albums.AddToAlbumSheet
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionRequest
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionState
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.albumAdditionMessage
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.MediaViewportLoadingMode
import com.syrok0010.nextgallery.feature.deletion.DeleteSelectionDialog
import com.syrok0010.nextgallery.feature.deletion.DeletionPreparation
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionState
import com.syrok0010.nextgallery.feature.deletion.mediaDeletionMessage
import com.syrok0010.nextgallery.feature.deletion.rememberDeleteAction
import com.syrok0010.nextgallery.feature.timeline.daysForSlots
import com.syrok0010.nextgallery.feature.upload.UploadBatch
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import com.syrok0010.nextgallery.feature.upload.uploadSelections
import org.koin.compose.koinInject

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    timeline: TimelineProjectionStore = koinInject(),
    coordinator: MediaLibraryCoordinator = koinInject(),
    additions: AlbumAdditions = koinInject(),
    uploads: UploadQueue = koinInject(),
    deletions: MediaDeletionRepository = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    val addition by additions.state.collectAsStateWithLifecycle()
    val deletion by deletions.state.collectAsStateWithLifecycle()
    var albumRequest by remember { mutableStateOf<Pair<AlbumAdditionRequest, () -> Unit>?>(null) }
    var deleteRequest by remember { mutableStateOf<DeleteRequest?>(null) }
    val delete = rememberDeleteAction(deletions::delete)
    MediaCollectionScreen(
        slots = state.snapshot?.slots.orEmpty(),
        emptyContent = { Text(stringResource(R.string.timeline_empty)) },
        onViewportObservation = {
            coordinator.requestDays(
                state.snapshot
                    ?.daysForSlots(
                        it.firstVisibleSlotIndex..it.lastVisibleSlotIndex,
                    ).orEmpty(),
                it.loadingMode == MediaViewportLoadingMode.Debounced,
            )
        },
        onViewerRange = {
            coordinator.requestDays(state.snapshot?.daysForSlots(it).orEmpty())
        },
        onViewerVisibilityChanged = onViewerVisibilityChanged,
        onSelectionModeChanged = onSelectionModeChanged,
        selectionEnabled = true,
        onUpload = { items, onStarted ->
            // The batch starts from the app root, which also asks for the notification permission.
            uploads.submit(UploadBatch(uploadSelections(items)))
            onStarted()
        },
        onAddToAlbum = { items, onDone -> albumRequest = AlbumAdditionRequest.of(items) to onDone },
        onDelete = { items, onDone ->
            deletions.prepare(items.map { it.mediaId })?.let { preparation ->
                deleteRequest = DeleteRequest(preparation, onDone)
            }
        },
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Photos,
            onLogout,
            viewerVisible = viewerVisible,
        ) {
            content()
            if (!viewerVisible) {
                // Keeps results above the library island once selection mode ends.
                Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp)) {
                    OperationSnackbar(
                        state = addition,
                        message = albumAdditionMessage(
                            addition,
                        )?.map { it.asString() }?.joinToString(", "),
                        running = addition is AlbumAdditionState.Running,
                        onShown = additions::consume,
                    )
                    OperationSnackbar(
                        state = deletion,
                        message = mediaDeletionMessage(
                            deletion,
                        )?.map { it.asString() }?.joinToString(". "),
                        running = deletion is MediaDeletionState.Running,
                        onShown = deletions::consume,
                    )
                }
            }
        }
    }
    deleteRequest?.let { (preparation, onDone) ->
        DeleteSelectionDialog(
            plan = preparation.plan,
            onDismiss = { deleteRequest = null },
            onConfirm = { scope ->
                deleteRequest = null
                delete(preparation.confirm(scope), onDone)
            },
        )
    }
    albumRequest?.let { (request, onDone) ->
        AddToAlbumSheet(
            request = request,
            onDismiss = { albumRequest = null },
            onPick = { target ->
                // A rejected request keeps the sheet and the selection; the sheet explains why.
                if (additions.add(target, request)) {
                    albumRequest = null
                    onDone()
                }
            },
        )
    }
}

/** [onDone] ends selection mode; it runs only once the deletion is confirmed. */
private data class DeleteRequest(val preparation: DeletionPreparation, val onDone: () -> Unit)

/** Shows a running message until replaced, then the result once; [state] keys the effect. */
@Composable
private fun OperationSnackbar(
    state: Any,
    message: String?,
    running: Boolean,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, message, lifecycle) {
        if (message == null) return@LaunchedEffect
        // A result timing out while the app is stopped would be consumed unseen; show it again
        // once the screen is visible.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (running) {
                // Replaced by the result: a new state cancels this effect and dismisses it.
                host.showSnackbar(message, duration = SnackbarDuration.Indefinite)
            } else {
                host.showSnackbar(
                    message,
                    withDismissAction = true,
                    duration = SnackbarDuration.Long,
                )
                onShown()
            }
        }
    }
    SnackbarHost(host, modifier)
}
