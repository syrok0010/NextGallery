package com.syrok0010.nextgallery.feature.timeline

import androidx.lifecycle.ViewModel
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.AppMessageUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode

data class TimelineScreenState(
    val isSignedIn: Boolean = false,
    val timeline: TimelineUiState = TimelineUiState(),
    val isBusy: Boolean = false,
    val sourceDiagnostics: List<UiText> = emptyList(),
    val message: AppMessageUiState = AppMessageUiState(),
    val localMediaPermissionMode: LocalMediaPermissionMode? = null,
)

internal class TimelineViewModel(private val timeline: TimelineRepository) : ViewModel() {
    val state = timeline.state
    fun refresh() = timeline.refresh()
    internal fun observeTimelineViewport(observation: TimelineViewportObservation) = timeline.observeViewport(observation)
    fun loadVisibleTimelineRange(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        observeTimelineViewport(TimelineViewportObservation(firstVisibleIndex, lastVisibleIndex, TimelineViewportLoadingMode.Immediate))
    }
}

internal fun TimelineWorkflowState.toUiState(): TimelineScreenState {
    // Independent source states survive each other's updates; errors take precedence over progress.
    val error = when {
        remote == TimelineOperation.Failed -> uiText(R.string.error_load_memories_api_failed)
        local == TimelineOperation.Failed -> uiText(R.string.error_load_local_media_failed)
        else -> null
    }
    val status = when {
        remote == TimelineOperation.Loading -> uiText(R.string.status_loading_memories_api)
        local is TimelineOperation.Indexing -> uiText(R.string.status_indexing_local_media, local.indexed, local.total)
        else -> uiText(R.string.status_loaded_items, snapshot?.items?.size ?: 0)
    }
    return TimelineScreenState(
        isSignedIn = true,
        timeline = TimelineUiState(snapshot, loadingDayIds, failedDayIds,
            if (failedDayIds.isEmpty()) null else uiText(R.string.error_load_timeline_batch_failed)),
        isBusy = remote == TimelineOperation.Loading,
        sourceDiagnostics = listOfNotNull(
            when (remote) {
                TimelineOperation.Loading -> uiText(R.string.status_loading_memories_api)
                TimelineOperation.Failed -> uiText(R.string.error_load_memories_api_failed)
                else -> uiText(R.string.diagnostics_remote_idle)
            },
            when (local) {
                is TimelineOperation.Indexing -> uiText(R.string.status_indexing_local_media, local.indexed, local.total)
                TimelineOperation.Failed -> uiText(R.string.error_load_local_media_failed)
                else -> uiText(R.string.diagnostics_local_idle)
            },
            lastLocalProgress?.let { uiText(R.string.diagnostics_local_processed, it.indexed, it.total) },
        ),
        message = AppMessageUiState(status = status, error = error),
        localMediaPermissionMode = permission,
    )
}
