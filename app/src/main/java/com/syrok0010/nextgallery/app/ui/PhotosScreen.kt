package com.syrok0010.nextgallery.app.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.feature.timeline.TimelineViewModel
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun PhotosScreen(
    onLogout: () -> Unit,
    onViewerVisibilityChanged: (Boolean) -> Unit,
    viewModel: TimelineViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    MediaCollectionScreen(
        slots = state.timeline.snapshot?.slots.orEmpty(),
        emptyContent = { Text(stringResource(R.string.timeline_empty)) },
        onViewportObservation = viewModel::observeTimelineViewport,
        onViewerRange = { viewModel.loadVisibleTimelineRange(it.first, it.last) },
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
