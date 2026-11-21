package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.upload.LogoutRefusal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class SessionViewModel(
    private val sessionStore: SessionStore,
    private val endSession: suspend () -> LogoutRefusal?,
) : ViewModel() {
    /** Why the last logout kept the session; the screen explains it and resets it to null. */
    val logoutRefusal = MutableStateFlow<LogoutRefusal?>(null)

    fun logout() {
        viewModelScope.launch {
            // On success the process ends; the next start is a fresh install.
            logoutRefusal.value = endSession()
        }
    }
    val session: StateFlow<SessionUiState> = sessionStore.session
}
