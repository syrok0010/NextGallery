package com.syrok0010.nextgallery.ui

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

class SessionViewModel(
    sessionStore: SessionStore,
) : ViewModel() {
    val session: StateFlow<SessionUiState> = sessionStore.session
}
