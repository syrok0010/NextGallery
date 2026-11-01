package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class SessionViewModel(
    private val sessionStore: SessionStore,
    private val credentials: CredentialsStore,
    private val memories: MemoriesRepository,
    private val uploads: com.syrok0010.nextgallery.feature.upload.UploadQueue,
) : ViewModel() {
    val logoutBlocked = kotlinx.coroutines.flow.MutableStateFlow(false)
    fun logout() {
        viewModelScope.launch {
            val allowed = uploads.logout {
                credentials.clear()
                sessionStore.signOut()
            }
            logoutBlocked.value = !allowed
            if (allowed) memories.clearCache()
        }
    }
    val session: StateFlow<SessionUiState> = sessionStore.session
}
