package com.syrok0010.nextgallery.app.ui

import androidx.lifecycle.ViewModel
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    sessionStore: SessionStore,
) : ViewModel() {
    val session: StateFlow<SessionUiState> = sessionStore.session
}
