package com.syrok0010.nextgallery.ui.timeline

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
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey

@Composable
internal fun TimelineGrid(
    gridItems: List<TimelineGridItem>,
    gridState: LazyGridState,
    thumbnailKeys: Map<Long, ThumbnailKey>,
    registerTimelineTile: (fileId: Long, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
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
                    thumbnailKey = item.slot.mediaItem
                        ?.let { thumbnailKeys[it.fileId] },
                    registerTimelineTile = registerTimelineTile,
                    onSelect = onSelect,
                )
            }
        }
    }
}
