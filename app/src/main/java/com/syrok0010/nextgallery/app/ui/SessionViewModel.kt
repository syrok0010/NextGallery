package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import kotlinx.coroutines.launch
import androidx.lifecycle.ViewModel
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    private val sessionStore: SessionStore,
    private val credentials: CredentialsStore,
    private val memories: MemoriesRepository,
) : ViewModel() {
    fun logout() {
        credentials.clear()
        viewModelScope.launch { memories.clearCache() }
        sessionStore.signOut()
    }
    val session: StateFlow<SessionUiState> = sessionStore.session
}
