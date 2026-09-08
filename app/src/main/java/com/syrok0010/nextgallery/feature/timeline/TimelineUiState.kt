package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.ui.UiText

data class TimelineUiState(
    val snapshot: TimelineSnapshot? = null,
    val loadingDayIds: Set<Int> = emptySet(),
    val failedDayIds: Set<Int> = emptySet(),
    val loadMoreError: UiText? = null,
)

internal fun TimelineUiState.withRefreshedSnapshot(refreshedSnapshot: TimelineSnapshot): TimelineUiState {
    val refreshedDayIds = refreshedSnapshot.days.mapTo(mutableSetOf()) { it.dayId }

    return copy(
        snapshot = refreshedSnapshot,
        loadingDayIds = loadingDayIds
            .intersect(refreshedDayIds)
            .minus(refreshedSnapshot.loadedDayIds),
        failedDayIds = emptySet(),
        loadMoreError = null,
    )
}
