package com.syrok0010.nextgallery.feature.auth

sealed interface LoginAttempt {
    data object Idle : LoginAttempt
    data object Starting : LoginAttempt
    data class Awaiting(val session: LoginSession, val browserOpened: Boolean = false) : LoginAttempt
    data object SavingCredentials : LoginAttempt
    data object Failed : LoginAttempt
}

data class LoginUiState(
    val serverUrlInput: String = "",
    val attempt: LoginAttempt = LoginAttempt.Idle,
) {
    val session: LoginSession? get() = (attempt as? LoginAttempt.Awaiting)?.session
    val browserOpened: Boolean get() = (attempt as? LoginAttempt.Awaiting)?.browserOpened ?: false
    val isPolling: Boolean get() = attempt is LoginAttempt.Awaiting
}
