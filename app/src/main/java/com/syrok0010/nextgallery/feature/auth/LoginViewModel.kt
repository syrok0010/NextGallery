package com.syrok0010.nextgallery.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.ui.AppMessageUiState
import com.syrok0010.nextgallery.core.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds

data class LoginScreenUiState(
    val login: LoginUiState = LoginUiState(),
    val message: AppMessageUiState = AppMessageUiState(),
) {
    val isBusy: Boolean get() = login.attempt == LoginAttempt.Starting || login.attempt == LoginAttempt.SavingCredentials
}

class LoginViewModel(
    private val sessionStore: SessionStore,
    private val credentialsStore: CredentialsStore,
    private val gateway: LoginGateway,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutableState = MutableStateFlow(LoginScreenUiState())
    val state = mutableState.asStateFlow()
    private var attemptJob: Job? = null

    fun updateServerUrl(value: String) {
        mutableState.update { it.copy(login = it.login.copy(serverUrlInput = value)) }
    }

    fun startLogin() {
        if (state.value.login.attempt == LoginAttempt.SavingCredentials) return
        attemptJob?.cancel()
        val server = state.value.login.serverUrlInput.trim()
        if (server.isBlank()) {
            fail(R.string.error_enter_nextcloud_url)
            return
        }
        setAttempt(LoginAttempt.Starting, R.string.status_creating_login_flow)
        attemptJob = viewModelScope.launch {
            try {
                val session = gateway.startLogin(server)
                setAttempt(
                    LoginAttempt.Awaiting(session),
                    R.string.status_open_browser_confirm_login
                )
                val result = withTimeoutOrNull(120_000.milliseconds) {
                    while (true) {
                        delay(2_000.milliseconds)
                        when (val poll = gateway.pollLogin(session)) {
                            LoginPollResult.Pending -> status(R.string.status_login_not_confirmed_yet)
                            is LoginPollResult.Ready -> return@withTimeoutOrNull poll
                            is LoginPollResult.Failed -> {
                                if (!poll.isRecoverable) return@withTimeoutOrNull poll
                                status(R.string.status_login_poll_network_retrying)
                            }
                        }
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                }
                when (result) {
                    is LoginPollResult.Ready -> {
                        setAttempt(
                            LoginAttempt.SavingCredentials,
                            R.string.status_login_complete_loading_timeline
                        )
                        try {
                            withContext(storageDispatcher) { credentialsStore.save(result.credentials) }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            fail(R.string.error_save_credentials)
                            return@launch
                        }
                        sessionStore.signIn(result.credentials)
                        mutableState.value = LoginScreenUiState()
                    }

                    is LoginPollResult.Failed -> {
                        mutableState.update {
                            it.copy(
                                login = it.login.copy(attempt = LoginAttempt.Failed),
                                message = AppMessageUiState(error = result.failure.toUiText())
                            )
                        }
                    }

                    else -> fail(R.string.error_login_confirmation_timeout)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                fail(R.string.error_start_login_flow_failed)
            }
        }
    }

    fun reportLoginBrowserOpenFailure() {
        markBrowserOpened()
        mutableState.update { it.copy(message = AppMessageUiState(error = uiText(R.string.error_open_browser_failed))) }
    }

    fun markBrowserOpened() {
        mutableState.update { state ->
            val awaiting = state.login.attempt as? LoginAttempt.Awaiting ?: return@update state
            state.copy(login = state.login.copy(attempt = awaiting.copy(browserOpened = true)))
        }
    }

    fun cancelLogin() {
        if (state.value.login.attempt == LoginAttempt.SavingCredentials) return
        attemptJob?.cancel()
        fail(R.string.error_login_cancelled)
    }

    private fun setAttempt(attempt: LoginAttempt, statusRes: Int) {
        mutableState.update {
            it.copy(
                login = it.login.copy(attempt = attempt),
                message = AppMessageUiState(status = uiText(statusRes))
            )
        }
    }

    private fun status(res: Int) =
        mutableState.update { it.copy(message = AppMessageUiState(status = uiText(res))) }

    private fun fail(res: Int) {
        mutableState.update {
            it.copy(
                login = it.login.copy(attempt = LoginAttempt.Failed),
                message = AppMessageUiState(error = uiText(res))
            )
        }
    }
}

private fun LoginPollFailure.toUiText() = when (this) {
    is LoginPollFailure.Http -> uiText(R.string.error_login_poll_http, code)
    LoginPollFailure.Network -> uiText(R.string.error_login_poll_network)
    LoginPollFailure.Unknown -> uiText(R.string.error_login_poll_unknown)
}
