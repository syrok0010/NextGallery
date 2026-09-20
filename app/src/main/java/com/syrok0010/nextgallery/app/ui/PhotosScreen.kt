package com.syrok0010.nextgallery.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndexer
import com.syrok0010.nextgallery.feature.library.TimelineProjectionStore
import com.syrok0010.nextgallery.feature.timeline.TimelineViewportLoadingMode
import com.syrok0010.nextgallery.feature.timeline.TimelineViewportObservation
import org.koin.compose.koinInject

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    timeline: TimelineProjectionStore = koinInject(),
    indexer: MediaLibraryIndexer = koinInject(),
) {
    val state by timeline.state.collectAsStateWithLifecycle()
    MediaCollectionScreen(
        slots = state.timeline.snapshot?.slots.orEmpty(),
        emptyContent = { Text(stringResource(R.string.timeline_empty)) },
        onViewportObservation = indexer::observeViewport,
        onViewerRange = {
            indexer.observeViewport(
                TimelineViewportObservation(
                    it.first,
                    it.last,
                    TimelineViewportLoadingMode.Immediate,
                ),
            )
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
