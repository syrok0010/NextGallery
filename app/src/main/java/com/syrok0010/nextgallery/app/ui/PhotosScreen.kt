package com.syrok0010.nextgallery.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.feature.albums.AddToAlbumSheet
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionRequest
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.MediaViewportLoadingMode
import com.syrok0010.nextgallery.feature.collection.SelectionAction
import com.syrok0010.nextgallery.feature.collection.SelectionActionDone
import com.syrok0010.nextgallery.feature.collection.rememberMediaSelection
import com.syrok0010.nextgallery.feature.deletion.CloudTrashStatus
import com.syrok0010.nextgallery.feature.deletion.DeleteSelectionDialog
import com.syrok0010.nextgallery.feature.deletion.DeletionPreparation
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.deletion.NextcloudTrashBin
import com.syrok0010.nextgallery.feature.deletion.rememberDeleteAction
import com.syrok0010.nextgallery.feature.timeline.daysForSlots
import com.syrok0010.nextgallery.feature.upload.EnqueueResult
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onOpenUploads: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    onSelectionModeChanged: (Boolean) -> Unit = {},
    timeline: TimelineProjectionStore = koinInject(),
    coordinator: MediaLibraryCoordinator = koinInject(),
    additions: AlbumAdditions = koinInject(),
    uploads: UploadQueue = koinInject(),
    deletions: MediaDeletionRepository = koinInject(),
    trashBin: NextcloudTrashBin = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    val operations = rememberOperationStatus()
    val tileActivity = remember(operations) { operations::tileActivity }
    val scope = rememberCoroutineScope()
    var albumRequest by remember { mutableStateOf<Pair<AlbumAdditionRequest, () -> Unit>?>(null) }
    var deleteRequest by remember { mutableStateOf<DeletionPreparation?>(null) }
    // Asked on every dialog, so the answer is fresh when the user decides.
    val cloudTrash by produceState(CloudTrashStatus.Checking, deleteRequest) {
        value = CloudTrashStatus.Checking
        if (deleteRequest?.plan?.hasCloudCopies == true) value = trashBin.status()
    }
    val selection = rememberMediaSelection()
    val delete = rememberDeleteAction(deletions::delete, onStarted = selection::clear)
    // The last objects the user asked to delete: the viewer stops holding its current object once
    // it leaves the library. One that keeps a copy stays in the library, so it is never released.
    var deletedIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val deletedMediaIds = remember(deletedIds) { deletedIds.mapTo(HashSet(), ::MediaId) }
    val deleteAction = SelectionAction(
        icon = rememberVectorPainter(Icons.Default.Delete),
        label = stringResource(R.string.delete_action),
        isDestructive = true,
    ) { items, _ ->
        deletedIds = items.map { it.mediaId.value }
        deletions.prepare(items.map { it.mediaId })?.let { deleteRequest = it }
    }
    val albumAction = SelectionAction(
        icon = painterResource(R.drawable.ic_albums),
        label = stringResource(R.string.album_add_action),
    ) { items, onDone -> albumRequest = AlbumAdditionRequest.of(items) to onDone }
    val uploadAction = SelectionAction(
        icon = painterResource(R.drawable.ic_cloud_upload),
        label = stringResource(R.string.upload_action),
        isPrimary = true,
        done = SelectionActionDone(
            icon = painterResource(R.drawable.ic_cloud_done),
            label = stringResource(R.string.viewer_in_cloud),
            appliesTo = { it.hasRemoteCopy },
        ),
    ) { items, onDone ->
        // The queue schedules the upload and the app root asks for notifications. A queue that
        // could not be saved keeps the selection, so the user can try again.
        scope.launch {
            if (uploads.submit(items.map { it.mediaId }) != EnqueueResult.Rejected) onDone()
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
        selection = selection,
        selectionActions = listOf(deleteAction, albumAction, uploadAction),
        viewerActions = listOf(albumAction, uploadAction, deleteAction),
        viewerStatus = { visible ->
            if (operations.active) {
                OperationStatusPill(
                    operations,
                    visible = visible,
                    onOpenUploads = onOpenUploads,
                    fillWidth = false,
                )
            }
        },
        deletedMediaIds = deletedMediaIds,
        tileActivity = tileActivity,
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Photos,
            onLogout,
            onOpenUploads,
            viewerVisible = viewerVisible,
            operations = operations,
        ) {
            content()
        }
    }
    deleteRequest?.let { preparation ->
        DeleteSelectionDialog(
            plan = preparation.plan,
            cloudTrash = cloudTrash,
            onDismiss = { deleteRequest = null },
            onConfirm = { scope ->
                deleteRequest = null
                delete(preparation.confirm(scope, cloudAllowed = cloudTrash.allowsCloud))
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
