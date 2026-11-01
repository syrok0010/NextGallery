package com.syrok0010.nextgallery.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.feature.favorites.*
import com.syrok0010.nextgallery.feature.collection.toMediaSlots
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.app.library.TimelineProjectionStore
import com.syrok0010.nextgallery.feature.albums.AddToAlbumSheet
import com.syrok0010.nextgallery.feature.albums.AlbumAdditionRequest
import com.syrok0010.nextgallery.feature.albums.AlbumAdditions
import com.syrok0010.nextgallery.feature.collection.MediaCollectionScreen
import com.syrok0010.nextgallery.feature.collection.MediaViewportLoadingMode
import com.syrok0010.nextgallery.feature.collection.SelectionAction
import com.syrok0010.nextgallery.feature.collection.rememberMediaSelection
import com.syrok0010.nextgallery.feature.deletion.DeleteSelectionDialog
import com.syrok0010.nextgallery.feature.deletion.DeletionPreparation
import com.syrok0010.nextgallery.feature.deletion.MediaDeletionRepository
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
    favorites: CloudFavorites = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    val favoriteState by favorites.state.collectAsStateWithLifecycle()
    LaunchedEffect(favoritesOnly) { if (favoritesOnly) favorites.refresh() }
    FavoriteError(favorites)
    val shownSlots = remember(favoritesOnly, state.snapshot, favoriteState.items) {
        if (favoritesOnly) favoriteState.items.toMediaSlots() else state.snapshot?.slots.orEmpty()
    }
    val operations = rememberOperationStatus(uploads, additions, deletions)
    val tileActivity = remember(operations) { operations::tileActivity }
    var albumRequest by remember { mutableStateOf<Pair<AlbumAdditionRequest, () -> Unit>?>(null) }
    var deleteRequest by remember { mutableStateOf<DeletionPreparation?>(null) }
    val selection = rememberMediaSelection()
    LaunchedEffect(favoritesOnly, favoriteState.departed) { if (favoritesOnly) selection.remove(favoriteState.departed) }
    val delete = rememberDeleteAction(deletions::delete, onStarted = selection::clear)
    val completedDeletions by deletions.removedMediaIds.collectAsStateWithLifecycle()
    val mediaActions = listOf(
        SelectionAction(
            icon = rememberVectorPainter(Icons.Default.Delete),
            label = stringResource(R.string.delete_action),
            isDestructive = true,
        ) { items, _ ->
            // Server-filtered favorites may not be hydrated into the ordinary timeline index.
            deleteRequest = DeletionPreparation.of(items)
        },
        SelectionAction(
            icon = painterResource(R.drawable.ic_albums),
            label = stringResource(R.string.album_add_action),
        ) { items, onDone -> albumRequest = AlbumAdditionRequest.of(items) to onDone },
    )
    MediaCollectionScreen(
        slots = shownSlots,
        emptyContent = { Text(stringResource(when {
            !favoritesOnly -> R.string.timeline_empty
            favoriteState.loading -> R.string.favorite_loading
            !favoriteState.loaded -> R.string.favorite_no_cache
            else -> R.string.favorite_empty
        })) },
        onViewportObservation = {
            if (!favoritesOnly) coordinator.requestDays(
                state.snapshot
                    ?.daysForSlots(
                        it.firstVisibleSlotIndex..it.lastVisibleSlotIndex,
                    ).orEmpty(),
                it.loadingMode == MediaViewportLoadingMode.Debounced,
            )
        },
        onViewerRange = {
            if (!favoritesOnly) coordinator.requestDays(state.snapshot?.daysForSlots(it).orEmpty())
        },
        onViewerVisibilityChanged = onViewerVisibilityChanged,
        onSelectionModeChanged = onSelectionModeChanged,
        selectionEnabled = true,
        selection = selection,
        viewerActions = mediaActions,
        viewerAccessory = { item, enabled -> FavoriteViewerAction(item, favorites, enabled) },
        viewerItemNote = { FavoriteItemNote(it) },
        viewerStatus = { visible ->
            if (operations.active) {
                OperationStatusPill(
                    operations,
                    visible = visible && albumRequest == null && deleteRequest == null && !delete.awaitingConfirmation,
                )
            }
        },
        completedDeletions = completedDeletions + if (favoritesOnly) favoriteState.departed else emptySet(),
        selectionActions = mediaActions +
            listOf(
                SelectionAction(
                    icon = painterResource(R.drawable.ic_cloud),
                    label = stringResource(R.string.upload_action),
                    isPrimary = true,
                    blocksSelectionUntilDone = true,
                ) { items, onDone ->
                    // The app root starts the batch and asks for the notification permission.
                    uploads.submit(UploadBatch(uploadSelections(items)))
                    onDone()
                },
            ),
        tileActivity = tileActivity,
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Photos,
            onLogout,
            viewerVisible = viewerVisible,
            operations = operations,
            statusVisible = albumRequest == null && deleteRequest == null && !delete.awaitingConfirmation,
            onRefresh = { if (favoritesOnly) favorites.refresh() },
        ) {
            Column {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !favoritesOnly, onClick = { selection.clear(); favoritesOnly = false }, label = { Text(stringResource(R.string.favorite_filter_all)) })
                    FilterChip(selected = favoritesOnly, onClick = { selection.clear(); favoritesOnly = true }, label = { Text(stringResource(R.string.favorite_filter_cloud)) })
                }
                if (favoritesOnly) {
                    if (favoriteState.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (favoriteState.failed || !favoriteState.loaded) {
                        TextButton(onClick = favorites::refresh) {
                            Text(stringResource(if (favoriteState.failed) R.string.favorite_filter_failed else R.string.favorite_filter_cached))
                        }
                    }
                }
                Box(Modifier.weight(1f)) { content() }
            }
        }
    }
    deleteRequest?.let { preparation ->
        DeleteSelectionDialog(
            plan = preparation.plan,
            onDismiss = { deleteRequest = null },
            onConfirm = { scope ->
                deleteRequest = null
                delete(preparation.confirm(scope))
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
