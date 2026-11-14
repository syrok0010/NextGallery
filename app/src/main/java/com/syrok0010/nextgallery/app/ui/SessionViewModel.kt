package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import com.syrok0010.nextgallery.feature.upload.LogoutRefusal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class SessionViewModel(
    private val sessionStore: SessionStore,
    private val credentials: CredentialsStore,
    private val memories: MemoriesRepository,
    private val uploads: UploadQueue,
) : ViewModel() {
    /** Why the last logout kept the session; the screen explains it and resets it to null. */
    val logoutRefusal = MutableStateFlow<LogoutRefusal?>(null)

    fun logout() {
        viewModelScope.launch {
            val refusal = uploads.logout {
                credentials.clear()
                sessionStore.signOut()
            }
            logoutRefusal.value = refusal
            if (refusal == null) memories.clearCache()
        }
    }
    val session: StateFlow<SessionUiState> = sessionStore.session
}
