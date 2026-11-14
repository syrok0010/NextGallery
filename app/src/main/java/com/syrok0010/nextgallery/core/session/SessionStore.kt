package com.syrok0010.nextgallery.core.session

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(credentialsStore: CredentialsStore) {
    private val _session = MutableStateFlow(
        credentialsStore.load()?.let(SessionUiState::SignedIn) ?: SessionUiState.SignedOut,
    )

    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    fun signIn(credentials: com.syrok0010.nextgallery.core.session.AccountCredentials) {
        _session.value = SessionUiState.SignedIn(credentials)
    }

    /** Tests only: logout erases the app data and ends the process (ADR 0010). */
    @VisibleForTesting
    fun signOut() {
        _session.value = SessionUiState.SignedOut
    }

    fun requireCredentials(): AccountCredentials =
        (_session.value as? SessionUiState.SignedIn)?.credentials
            ?: error("Authenticated session required")
}
