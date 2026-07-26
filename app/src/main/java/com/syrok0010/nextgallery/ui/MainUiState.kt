package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot

sealed interface SessionUiState {
    data object SignedOut : SessionUiState
    data class SignedIn(val credentials: AccountCredentials) : SessionUiState
}

data class LoginUiState(
    val serverUrlInput: String = "",
    val session: LoginSession? = null,
    val browserOpened: Boolean = false,
    val isPolling: Boolean = false,
)

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
        failedDayIds = failedDayIds
            .intersect(refreshedDayIds)
            .minus(refreshedSnapshot.loadedDayIds),
        loadMoreError = null,
    )
}

data class AppMessageUiState(
    val status: UiText? = null,
    val error: UiText? = null,
)
