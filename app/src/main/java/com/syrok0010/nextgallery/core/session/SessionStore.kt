package com.syrok0010.nextgallery.core.session

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionStore(
    credentialsStore: CredentialsStore,
    private val prepare: (AccountCredentials) -> Unit = {},
) {
    private val _session = MutableStateFlow(
        credentialsStore.load()?.let(SessionUiState::SignedIn) ?: SessionUiState.SignedOut,
    )

    var generation: Long = 0
        private set

    private val changes = MutableStateFlow(generation)
    val generations = changes.asStateFlow()

    val session: StateFlow<SessionUiState> = _session.asStateFlow()

    fun signIn(credentials: com.syrok0010.nextgallery.core.session.AccountCredentials) {
        prepare(credentials)
        generation++
        _session.value = SessionUiState.SignedIn(credentials)
        changes.value = generation
    }

    /** Tests only: logout erases the app data and ends the process (ADR 0010). */
    @VisibleForTesting
    fun signOut() {
        generation++
        _session.value = SessionUiState.SignedOut
        changes.value = generation
    }

    fun requireCredentials(): AccountCredentials =
        (_session.value as? SessionUiState.SignedIn)?.credentials
            ?: error("Authenticated session required")
}
