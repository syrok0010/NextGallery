package com.syrok0010.nextgallery.core.session

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(
    credentialsStore: CredentialsStore,
    private val prepare: (AccountCredentials) -> Unit = {
    },
) {
    private val _session = MutableStateFlow(
        credentialsStore.load()?.let(SessionUiState::SignedIn) ?: SessionUiState.SignedOut,
    )

    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    init {
        (_session.value as? SessionUiState.SignedIn)?.credentials?.let(prepare)
    }

    fun signIn(credentials: com.syrok0010.nextgallery.core.session.AccountCredentials) {
        prepare(credentials)
        _session.value = SessionUiState.SignedIn(credentials)
    }

    fun requireCredentials(): AccountCredentials =
        (_session.value as? SessionUiState.SignedIn)?.credentials
            ?: error("Authenticated session required")
}
