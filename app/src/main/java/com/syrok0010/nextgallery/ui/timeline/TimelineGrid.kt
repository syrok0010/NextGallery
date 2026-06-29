package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview

@Composable
internal fun TimelineGrid(
    gridItems: List<TimelineGridItem>,
    gridState: LazyGridState,
    thumbnailPreviews: Map<Long, ThumbnailPreview>,
    credentials: AccountCredentials,
    sharedTransitionScope: SharedTransitionScope,
    animatedVisibilityScope: AnimatedVisibilityScope,
    enableSharedElements: Boolean,
    onTileBoundsChanged: (fileId: Long, bounds: Rect?) -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(
            items = gridItems,
            key = { _, item -> item.key },
            span = { _, item ->
                when (item) {
                    is TimelineGridItem.DayHeader -> GridItemSpan(maxLineSpan)
                    is TimelineGridItem.Slot -> GridItemSpan(1)
                }
            },
        ) { _, item ->
            when (item) {
                is TimelineGridItem.DayHeader -> TimelineDayHeader(item.dayId)
                is TimelineGridItem.Slot -> TimelineSlotTile(
                    slot = item.slot,
                    credentials = credentials,
                    sharedTransitionScope = sharedTransitionScope,
                    animatedVisibilityScope = animatedVisibilityScope,
                    enableSharedElement = enableSharedElements,
                    thumbnailPreview = item.slot.mediaItem
                        ?.let { thumbnailPreviews[it.fileId] },
                    onBoundsChanged = onTileBoundsChanged,
                    onSelect = onSelect,
                )
            }
        }
    }
}
