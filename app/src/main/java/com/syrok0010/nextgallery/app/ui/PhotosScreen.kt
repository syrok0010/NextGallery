package com.syrok0010.nextgallery.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.timeline.TimelineCommands
import com.syrok0010.nextgallery.feature.timeline.TimelineLoadRange
import com.syrok0010.nextgallery.feature.timeline.TimelineProjectionStore
import com.syrok0010.nextgallery.feature.timeline.TimelineViewportLoadingMode
import org.koin.compose.koinInject

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    timeline: TimelineProjectionStore = koinInject(),
    timelineCommands: TimelineCommands = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    MediaCollectionScreen(
        slots = state.timeline.snapshot?.slots.orEmpty(),
        emptyContent = { Text(stringResource(R.string.timeline_empty)) },
        onViewportObservation = {
            timelineCommands.requestRange(
                TimelineLoadRange(
                    it.firstVisibleSlotIndex,
                    it.lastVisibleSlotIndex,
                    it.loadingMode == TimelineViewportLoadingMode.Debounced,
                ),
            )
        },
        onViewerRange = {
            timelineCommands.requestRange(TimelineLoadRange(it.first, it.last))
        },
        onViewerVisibilityChanged = onViewerVisibilityChanged,
    ) { viewerVisible, content ->
        LibraryScreenScaffold(
            TopLevelDestination.Photos,
            onLogout,
            viewerVisible = viewerVisible,
        ) {
            content()
        }
    }
}
