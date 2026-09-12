package com.syrok0010.nextgallery.core.session

sealed interface SessionUiState {
    data object SignedOut : SessionUiState
    data class SignedIn(val credentials: AccountCredentials) : SessionUiState
}
