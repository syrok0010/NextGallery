package com.syrok0010.nextgallery.feature.timeline

internal data class TimelineViewportObservation(
    val firstVisibleSlotIndex: Int,
    val lastVisibleSlotIndex: Int,
    val loadingMode: TimelineViewportLoadingMode,
)

internal enum class TimelineViewportLoadingMode {
    Immediate,
    Debounced,
}
