package com.syrok0010.nextgallery.feature.collection

internal data class MediaViewportObservation(
    val firstVisibleSlotIndex: Int,
    val lastVisibleSlotIndex: Int,
    val loadingMode: MediaViewportLoadingMode,
)

internal enum class MediaViewportLoadingMode {
    Immediate,
    Debounced,
}
