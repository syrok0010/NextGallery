package com.syrok0010.nextgallery.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(credentialsStore: CredentialsStore) {
    private val _session = MutableStateFlow(
        credentialsStore.load()?.let(SessionUiState::SignedIn) ?: SessionUiState.SignedOut,
    )

    var generation: Long = 0
        private set

    private val changes = MutableStateFlow(generation)
    val generations = changes.asStateFlow()

    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    fun signIn(credentials: com.syrok0010.nextgallery.core.session.AccountCredentials) {
        generation++
        _session.value = SessionUiState.SignedIn(credentials)
        changes.value = generation
    }

    fun signOut() {
        generation++
        _session.value = SessionUiState.SignedOut
        changes.value = generation
    }
}
