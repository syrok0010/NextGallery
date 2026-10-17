package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.albums.AddToAlbumSheet
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionRequest
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionState
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.albums.albumAdditionMessage
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.MediaViewportLoadingMode
import com.syrok0010.nextgallery.feature.timeline.daysForSlots
import com.syrok0010.nextgallery.feature.upload.UploadAlbum
import com.syrok0010.nextgallery.feature.upload.UploadForegroundService
import com.syrok0010.nextgallery.feature.upload.UploadHandoff
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.rememberUploadAction
import org.koin.compose.koinInject

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    timeline: TimelineProjectionStore = koinInject(),
    coordinator: MediaLibraryCoordinator = koinInject(),
    additions: AlbumAdditions = koinInject(),
    handoff: UploadHandoff = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    val addition by additions.state.collectAsStateWithLifecycle()
    var albumRequest by remember { mutableStateOf<AlbumSheetRequest?>(null) }
    val context = LocalContext.current
    val upload = rememberUploadAction { UploadForegroundService.start(context, it) }
    val pendingUploads by handoff.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pendingUploads) {
        if (pendingUploads.isNotEmpty()) {
            upload(pendingUploads) {}
            handoff.taken(pendingUploads)
        }
    }
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
        onUpload = { items, onStarted -> upload(uploadSelections(items), onStarted) },
        onAddToAlbum = { items, onDone ->
            albumRequest = AlbumSheetRequest(
                AlbumAdditionRequest.of(items),
                uploadSelections(items),
                onDone,
            )
        },
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Photos,
            onLogout,
            viewerVisible = viewerVisible,
        ) {
            content()
            if (!viewerVisible) {
                AlbumAdditionSnackbar(
                    addition,
                    onShown = additions::consume,
                    // Keeps the result above the library island once selection mode ends.
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
                )
            }
        }
    }
    albumRequest?.let { (request, uploads, onDone) ->
        AddToAlbumSheet(
            request = request,
            onDismiss = { albumRequest = null },
            onPick = { target ->
                // A rejected request keeps the sheet and the selection; the sheet explains why.
                val accepted = additions.add(target, request) { album ->
                    // May run after this screen is gone: hand off data only, never the screen.
                    handoff.offer(
                        uploads.map { it.copy(album = UploadAlbum(album.owner, album.name)) },
                    )
                }
                if (accepted) {
                    albumRequest = null
                    onDone()
                }
            },
        )
    }
}

/** Selection snapshot for the album sheet; [uploads] are local-only objects without server copy. */
private data class AlbumSheetRequest(
    val request: AlbumAdditionRequest,
    val uploads: List<UploadSelection>,
    val onDone: () -> Unit,
)

private fun uploadSelections(items: List<MediaItem>): List<UploadSelection> =
    items.filterNot { it.hasRemoteCopy }.mapNotNull { item ->
        item.localCopy?.let { local ->
            UploadSelection(
                contentUri = local.contentUri,
                displayName = item.displayName,
                mimeType = item.mimeType,
            )
        }
    }

@Composable
private fun AlbumAdditionSnackbar(
    state: AlbumAdditionState,
    onShown: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val host = remember { SnackbarHostState() }
    val message = albumAdditionMessage(state)?.map { it.asString() }?.joinToString(", ")
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(state, message, lifecycle) {
        if (message == null) return@LaunchedEffect
        // A result timing out while the app is stopped would be consumed unseen; show it again
        // once the screen is visible.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (state is AlbumAdditionState.Running) {
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
