package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot

data class MainUiState(
    val session: SessionUiState = SessionUiState.SignedOut(),
    val isBusy: Boolean = false,
    val message: AppMessageUiState = AppMessageUiState(),
)

sealed interface SessionUiState {
    data class SignedOut(
        val login: LoginUiState = LoginUiState(),
    ) : SessionUiState

    data class SignedIn(
        val credentials: AccountCredentials,
        val timeline: TimelineUiState = TimelineUiState(),
    ) : SessionUiState
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
    val thumbnailPreviews: Map<Long, ThumbnailPreview> = emptyMap(),
    val thumbnailLoadingFileIds: Set<Long> = emptySet(),
    val thumbnailFailedFileIds: Set<Long> = emptySet(),
)

data class AppMessageUiState(
    val status: UiText? = null,
    val error: UiText? = null,
)

val MainUiState.signedIn: SessionUiState.SignedIn?
    get() = session as? SessionUiState.SignedIn

val MainUiState.loginState: LoginUiState?
    get() = (session as? SessionUiState.SignedOut)?.login

val MainUiState.isLoginPolling: Boolean
    get() = loginState?.isPolling == true

fun MainUiState.updateLogin(transform: (LoginUiState) -> LoginUiState): MainUiState {
    val signedOut = session as? SessionUiState.SignedOut ?: return this
    return copy(session = signedOut.copy(login = transform(signedOut.login)))
}

fun MainUiState.updateTimeline(transform: (TimelineUiState) -> TimelineUiState): MainUiState {
    val signedIn = session as? SessionUiState.SignedIn ?: return this
    return copy(session = signedIn.copy(timeline = transform(signedIn.timeline)))
}
