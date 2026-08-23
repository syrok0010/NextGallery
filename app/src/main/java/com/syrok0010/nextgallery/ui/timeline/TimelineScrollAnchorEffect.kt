package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

@Composable
internal fun PreserveTimelineScrollAnchor(
    gridItems: List<TimelineGridItem>,
    gridState: LazyGridState,
    isScrollNavigationActive: Boolean,
) {
    val controller = remember { TimelineScrollAnchorController() }
    val baseline = remember { TimelineScrollAnchorBaseline() }
    val restoration = remember(gridItems, isScrollNavigationActive) {
        baseline.gridItems
            ?.takeIf { previousGridItems -> previousGridItems != gridItems }
            ?.let { previousGridItems ->
                val viewportStartOffset = gridState.layoutInfo.viewportStartOffset
                controller.restorationForUpdate(
                    previousGridItems = previousGridItems,
                    newGridItems = gridItems,
                    visibleItems = gridState.layoutInfo.visibleItemsInfo.map { item ->
                        TimelineVisibleGridItem(
                            gridIndex = item.index,
                            key = item.key.toString(),
                            viewportOffsetPx = item.offset.y - viewportStartOffset,
                        )
                    },
                    isRestorationAllowed = !isScrollNavigationActive && !gridState.isScrollInProgress,
                )
            }
    }
    LaunchedEffect(gridItems) {
        snapshotFlow {
            gridState.layoutInfo.visibleItemsInfo.map { item ->
                item.index to item.key.toString()
            }
        }.first { measuredItems ->
            measuredItems.isEmpty() || measuredItems.all { (index, key) ->
                gridItems.getOrNull(index)?.key == key
            }
        }
        baseline.gridItems = gridItems
    }

    LaunchedEffect(gridItems, restoration, isScrollNavigationActive) {
        restoration?.takeIf { !isScrollNavigationActive }?.let { target ->
            gridState.scrollToItem(
                index = target.gridIndex,
                scrollOffset = target.scrollOffsetPx,
            )
        }
    }
}

private class TimelineScrollAnchorBaseline {
    var gridItems: List<TimelineGridItem>? = null
}
