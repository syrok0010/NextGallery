package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.NextGalleryScaffold
import com.syrok0010.nextgallery.ui.SessionUiState
import com.syrok0010.nextgallery.ui.detail.MediaDetailScreen

@Composable
internal fun AuthenticatedDestinationScreen(
    session: SessionUiState.SignedIn?,
    message: AppMessageUiState,
    isBusy: Boolean,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    revealFileId: Long?,
    viewerFileId: Long?,
    appBounds: Rect?,
    timelineTileBoundsByFileId: Map<Long, Rect>,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onViewportObservation: (TimelineViewportObservation) -> Unit,
    onTimelineFileRevealed: () -> Unit,
    onTileBoundsChanged: (fileId: Long, bounds: Rect?) -> Unit,
    onSelect: (MediaItem) -> Unit,
    onViewerFileIdChange: (Long?) -> Unit,
    onRevealTimelineFile: (Long?) -> Unit,
    onVisibleTimelineRange: (firstVisibleIndex: Int, lastVisibleIndex: Int) -> Unit,
    onAppBoundsChanged: (Rect) -> Unit,
) {
    val viewerItems = session?.timeline?.snapshot
        ?.slots
        ?.mapIndexedNotNull { slotIndex, slot ->
            slot.mediaItem?.let { item ->
                ViewerMediaItem(
                    item = item,
                    slotIndex = slotIndex,
                )
            }
        }
        .orEmpty()
    val items = viewerItems.map { it.item }
    val slotIndexByFileId = viewerItems.associate { it.item.fileId to it.slotIndex }
    val visibleViewerFileId = viewerFileId?.takeIf { fileId ->
        items.any { it.fileId == fileId }
    }

    fun isTimelineTileVisible(fileId: Long): Boolean {
        val tileBounds = timelineTileBoundsByFileId[fileId] ?: return false
        val rootBounds = appBounds ?: return true
        return tileBounds.overlaps(rootBounds)
    }

    val visibleTimelineTileBoundsByFileId = timelineTileBoundsByFileId
        .filterValues { tileBounds ->
            appBounds?.let { rootBounds -> tileBounds.overlaps(rootBounds) } ?: true
        }

    NextGalleryScaffold(
        showTopBar = visibleViewerFileId == null,
        actions = {
            TextButton(onClick = onRefresh, enabled = !isBusy) {
                Text(stringResource(R.string.action_refresh))
            }
            TextButton(onClick = onLogout, enabled = !isBusy) {
                Text(stringResource(R.string.action_logout))
            }
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
        ) {
            if (session != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .onGloballyPositioned { coordinates ->
                            onAppBoundsChanged(coordinates.boundsInRoot())
                        },
                ) {
                    TimelinePanel(
                        state = session.timeline,
                        message = message,
                        credentials = session.credentials,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        enableSharedElements = visibleViewerFileId == null,
                        onViewportObservation = onViewportObservation,
                        revealFileId = revealFileId,
                        onFileRevealed = onTimelineFileRevealed,
                        onTileBoundsChanged = onTileBoundsChanged,
                        onSelect = onSelect,
                    )
                }
            }

            if (isBusy) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(padding)
                        .padding(16.dp)
                        .size(28.dp),
                )
            }

            if (session != null && visibleViewerFileId != null) {
                MediaDetailScreen(
                    initialFileId = visibleViewerFileId,
                    items = items,
                    slotIndexByFileId = slotIndexByFileId,
                    tileBoundsByFileId = visibleTimelineTileBoundsByFileId,
                    thumbnailPreviews = session.timeline.thumbnailPreviews,
                    credentials = session.credentials,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    onBack = { currentItem ->
                        if (!isTimelineTileVisible(currentItem.fileId)) {
                            onRevealTimelineFile(currentItem.fileId)
                        }
                        onViewerFileIdChange(null)
                    },
                    onCurrentItemChange = { currentItem ->
                        if (!isTimelineTileVisible(currentItem.fileId)) {
                            onRevealTimelineFile(currentItem.fileId)
                        }
                        onViewerFileIdChange(currentItem.fileId)
                    },
                    onVisibleTimelineRange = onVisibleTimelineRange,
                )
            }
        }
    }
}

private data class ViewerMediaItem(
    val item: MediaItem,
    val slotIndex: Int,
)
