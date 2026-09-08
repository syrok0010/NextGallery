package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(
    credentialsStore: CredentialsStore,
) {
    private val _session = MutableStateFlow(
        credentialsStore.load()?.let(SessionUiState::SignedIn) ?: SessionUiState.SignedOut,
    )

    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    fun signIn(credentials: com.syrok0010.nextgallery.data.credentials.AccountCredentials) {
        _session.value = SessionUiState.SignedIn(credentials)
    }

    fun signOut() {
        _session.value = SessionUiState.SignedOut
    }
}
