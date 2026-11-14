package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.upload.LogoutRefusal
import com.syrok0010.nextgallery.feature.upload.UploadQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

internal class SessionViewModel(
    private val sessionStore: SessionStore,
    private val uploads: UploadQueue,
    private val appData: AppDataEraser,
) : ViewModel() {
    /** Why the last logout kept the session; the screen explains it and resets it to null. */
    val logoutRefusal = MutableStateFlow<LogoutRefusal?>(null)

    fun logout() {
        viewModelScope.launch {
            // On success the process ends; the next start is a fresh install.
            logoutRefusal.value = uploads.logout(appData::erase)
        }
    }
    val session: StateFlow<SessionUiState> = sessionStore.session
}
