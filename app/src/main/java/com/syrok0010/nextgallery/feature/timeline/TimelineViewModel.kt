package com.syrok0010.nextgallery.feature.timeline

import androidx.lifecycle.ViewModel

data class TimelineScreenState(
    val timeline: TimelineUiState = TimelineUiState(),
)

internal class TimelineViewModel(private val timeline: TimelineRepository) : ViewModel() {
    val state = timeline.state
    fun refresh() = timeline.refresh()
    internal fun observeTimelineViewport(observation: TimelineViewportObservation) =
        timeline.observeViewport(observation)
    fun loadVisibleTimelineRange(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        observeTimelineViewport(
            TimelineViewportObservation(
                firstVisibleIndex,
                lastVisibleIndex,
                TimelineViewportLoadingMode.Immediate,
            ),
        )
    }
}