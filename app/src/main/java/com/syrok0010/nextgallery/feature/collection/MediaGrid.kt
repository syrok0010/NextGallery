package com.syrok0010.nextgallery.feature.collection

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import org.koin.compose.koinInject

@Composable
internal fun MediaGrid(
    gridItems: List<MediaGridItem>,
    gridState: LazyGridState,
    registerTimelineTile: (mediaId: MediaId, boundsProvider: () -> Rect?) -> () -> Unit,
    onSelect: (MediaItem) -> Unit,
    requestFactory: MediaImageRequestFactory = koinInject(),
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(116.dp),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            .testTag("media_grid"),
        contentPadding = PaddingValues(start = 2.dp, end = 2.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        itemsIndexed(
            items = gridItems,
            key = { _, item -> item.key },
            span = { _, item ->
                when (item) {
                    is MediaGridItem.DayHeader -> GridItemSpan(maxLineSpan)
                    is MediaGridItem.Slot -> GridItemSpan(1)
                }
            },
        ) { _, item ->
            when (item) {
                is MediaGridItem.DayHeader -> MediaDayHeader(item.dayId)

                is MediaGridItem.Slot -> MediaSlotTile(
                    slot = item.slot,
                    registerTimelineTile = registerTimelineTile,
                    onSelect = onSelect,
                    requestFactory = requestFactory,
                )
            }
        }
    }
}
