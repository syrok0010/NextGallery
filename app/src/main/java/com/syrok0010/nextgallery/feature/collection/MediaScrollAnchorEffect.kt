package com.syrok0010.nextgallery.feature.collection

import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first

/**
 * Keeps the screen on the same objects when the list changes under it, unless the user is moving
 * through the list themselves.
 */
@Composable
internal fun PreserveMediaScrollAnchor(
    layout: MediaGridLayout,
    gridState: LazyGridState,
    isScrollNavigationActive: Boolean,
) {
    val lastMeasured = remember { LastMeasuredLayout() }
    val restoration = remember(layout, isScrollNavigationActive) {
        val previous = lastMeasured.layout?.takeIf { it.items != layout.items }
        if (previous == null || isScrollNavigationActive || gridState.isScrollInProgress) {
            null
        } else {
            layout.restorationFrom(previous)
        }
    }
    LaunchedEffect(layout) {
        snapshotFlow { layout.isMeasured() }.first { it }
        lastMeasured.layout = layout
    }

    LaunchedEffect(layout, restoration, isScrollNavigationActive) {
        restoration?.takeIf { !isScrollNavigationActive }?.let { target ->
            gridState.scrollToItem(
                index = target.gridIndex,
                scrollOffset = target.scrollOffsetPx,
            )
        }
    }
}

/** The list the grid last measured: what the screen showed before the list changed. */
private class LastMeasuredLayout {
    var layout: MediaGridLayout? = null
}
