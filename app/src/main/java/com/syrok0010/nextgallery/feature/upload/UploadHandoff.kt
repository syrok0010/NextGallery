package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Uploads prepared outside composition, for example once an album exists. The visible screen
 * starts them because it owns the notification permission flow; a callback must not capture a
 * screen that may already be gone. Pending uploads never cross accounts.
 */
internal class UploadHandoff(sessions: SessionStore, scope: CoroutineScope) {
    private val mutablePending = MutableStateFlow<List<UploadSelection>>(emptyList())
    val pending = mutablePending.asStateFlow()

    init {
        scope.launch {
            var owner: AccountCredentials? = null
            sessions.session.collect { session ->
                val current = (session as? SessionUiState.SignedIn)?.credentials
                if (current == null || (owner != null && current != owner)) {
                    mutablePending.value = emptyList()
                }
                owner = current
            }
        }
    }

    fun offer(selections: List<UploadSelection>) {
        if (selections.isNotEmpty()) mutablePending.update { it + selections }
    }

    /** Called with the snapshot of [pending] the screen has handed to the upload service. */
    fun taken(selections: List<UploadSelection>) {
        mutablePending.update {
            if (it.take(selections.size) == selections) it.drop(selections.size) else it
        }
    }
}
