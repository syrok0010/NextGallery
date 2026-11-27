package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.albums.*
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.SelectionAction
import com.syrok0010.nextgallery.feature.collection.rememberMediaSelection
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import com.syrok0010.nextgallery.feature.deletion.CloudTrashStatus
import com.syrok0010.nextgallery.feature.deletion.DeleteSelectionDialog
import com.syrok0010.nextgallery.feature.deletion.DeletionPreparation
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
import com.syrok0010.nextgallery.feature.deletion.NextcloudTrashBin
import com.syrok0010.nextgallery.feature.deletion.rememberDeleteAction
import com.syrok0010.nextgallery.router.NextGalleryRoute
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf

@Composable
internal fun AlbumScreen(
    album: NextGalleryRoute.Album,
    viewModel: AlbumContentsViewModel = koinViewModel { parametersOf(album.location) },
    deletions: MediaDeletionRepository = koinInject(),
    trashBin: NextcloudTrashBin = koinInject(),
) {
    val contents by viewModel.state.collectAsStateWithLifecycle()
    val slots = remember(contents.items) { contents.items.toMediaSlots() }
    val operations = rememberOperationStatus()
    val tileActivity = remember(operations) { operations::tileActivity }
    val selection = rememberMediaSelection()
    val supported = viewModel.supportsActions
    val remote = album.location as? AlbumLocation.Remote
    var albumRequest by remember { mutableStateOf<Pair<AlbumAdditionRequest, () -> Unit>?>(null) }
    var deleteRequest by remember { mutableStateOf<DeletionPreparation?>(null) }
    var removeRequest by remember { mutableStateOf<Pair<Set<Long>, () -> Unit>?>(null) }
    val removing by viewModel.removing.collectAsStateWithLifecycle()
    val departedIds by viewModel.departedIds.collectAsStateWithLifecycle()
    // Asked on every dialog, so the answer is fresh when the user decides.
    val cloudTrash by produceState(CloudTrashStatus.Checking, deleteRequest) {
        value = CloudTrashStatus.Checking
        if (deleteRequest?.plan?.hasCloudCopies == true) value = trashBin.status()
    }
    val delete = rememberDeleteAction(deletions::delete, onStarted = selection::clear)
    LaunchedEffect(departedIds) { selection.remove(departedIds) }
    val actions = if (!supported) {
        emptyList()
    } else {
        buildList {
            if (remote != null) {
                add(
                    SelectionAction(
                        icon = painterResource(R.drawable.ic_album_remove),
                        label = stringResource(R.string.album_remove_action),
                        isPrimary = true,
                    ) { items, onDone ->
                        removeRequest = items.mapNotNull { it.remoteFileId }.toSet() to onDone
                    },
                )
            }
            add(
                SelectionAction(
                    icon = painterResource(R.drawable.ic_albums),
                    label = stringResource(R.string.album_add_other),
                    inOverflow = remote != null,
                ) { items, onDone -> albumRequest = AlbumAdditionRequest.of(items) to onDone },
            )
            add(
                SelectionAction(
                    icon = rememberVectorPainter(Icons.Default.Delete),
                    label = stringResource(R.string.delete_action),
                    isDestructive = true,
                    inOverflow = remote != null,
                ) { items, _ ->
                    // Album members may not have been hydrated into the timeline index yet.
                    deleteRequest = DeletionPreparation.of(items)
                },
            )
        }
    }
    MediaCollectionScreen(
        slots = slots,
        emptyContent = { AlbumContentsStatus(contents, viewModel::refresh) },
        tileActivity = tileActivity,
        selection = selection.takeIf { supported },
        selectionActions = actions,
        viewerActions = actions,
        // Members that left the album after a full refresh: the viewer stops holding them.
        deletedMediaIds = departedIds,
        viewerStatus = { visible ->
            if (operations.active) {
                OperationStatusPill(
                    operations,
                    visible = visible,
                    fillWidth = false,
                )
            }
        },
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Albums,
            viewerVisible = viewerVisible,
            onRefresh = viewModel::refresh,
            isSubscreen = true,
            title = album.title,
            operations = operations,
        ) {
            Column(Modifier.fillMaxSize()) {
                if (contents.items.isNotEmpty() && (contents.loading || contents.failed)) {
                    AlbumContentsStatus(contents, viewModel::refresh)
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
    removeRequest?.let { (fileIds, onDone) ->
        AlertDialog(
            onDismissRequest = { removeRequest = null },
            title = {
                Text(
                    stringResource(R.string.album_remove_title, album.title, fileIds.size),
                )
            },
            text = {
                Text(
                    stringResource(
                        if (removing) R.string.album_remove_busy else R.string.album_remove_note,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !removing && fileIds.isNotEmpty(),
                    onClick = {
                        if (remote != null && viewModel.removeMembers(album.title, fileIds)) {
                            removeRequest = null
                            onDone()
                        }
                    },
                ) { Text(stringResource(R.string.album_remove_action)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { removeRequest = null },
                ) { Text(stringResource(R.string.album_add_cancel)) }
            },
        )
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
            excludedLocation = album.location,
            onDismiss = { albumRequest = null },
            onPick = { target ->
                if (viewModel.addToOtherAlbum(target, request)) {
                    albumRequest = null
                    onDone()
                }
            },
        )
    }
}
