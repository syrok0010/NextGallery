package com.syrok0010.nextgallery.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.auth.LoginPollFailure
import com.syrok0010.nextgallery.data.auth.LoginPollResult
import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.LoginUiState
import com.syrok0010.nextgallery.ui.SessionStore
import com.syrok0010.nextgallery.ui.uiText
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class LoginScreenUiState(
    val login: LoginUiState = LoginUiState(),
    val isBusy: Boolean = false,
    val message: AppMessageUiState = AppMessageUiState(),
)

class LoginViewModel(
    private val sessionStore: SessionStore,
    private val credentialsStore: CredentialsStore,
    private val loginRepository: NextcloudLoginRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginScreenUiState())
    val state: StateFlow<LoginScreenUiState> = _state.asStateFlow()

    private var loginStartJob: Job? = null
    private var loginPollingJob: Job? = null
    private var loginAttemptId = 0L

    fun updateServerUrl(value: String) {
        _state.update { state ->
            state.copy(login = state.login.copy(serverUrlInput = value))
        }
    }

    fun startLogin() {
        loginAttemptId += 1
        val attemptId = loginAttemptId
        loginStartJob?.cancel()
        loginPollingJob?.cancel()
        val serverUrl = state.value.login.serverUrlInput.trim()
        if (serverUrl.isBlank()) {
            _state.update { state ->
                state.copy(
                    login = state.login.copy(
                        session = null,
                        browserOpened = false,
                        isPolling = false,
                    ),
                    isBusy = false,
                    message = AppMessageUiState(error = uiText(R.string.error_enter_nextcloud_url)),
                )
            }
            return
        }

        loginStartJob = viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    login = state.login.copy(
                        session = null,
                        browserOpened = false,
                        isPolling = false,
                    ),
                    isBusy = true,
                    message = AppMessageUiState(status = uiText(R.string.status_creating_login_flow)),
                )
            }

            try {
                val session = loginRepository.startLogin(serverUrl)
                if (attemptId != loginAttemptId) {
                    return@launch
                }

                loginStartJob = null
                _state.update { state ->
                    state.copy(
                        login = state.login.copy(
                            session = session,
                            browserOpened = false,
                            isPolling = true,
                        ),
                        isBusy = false,
                        message = AppMessageUiState(status = uiText(R.string.status_open_browser_confirm_login)),
                    )
                }
                startLoginPolling(session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (attemptId == loginAttemptId) {
                    loginStartJob = null
                    _state.update { state ->
                        state.copy(
                            login = state.login.copy(isPolling = false),
                            isBusy = false,
                            message = AppMessageUiState(error = uiText(R.string.error_start_login_flow_failed)),
                        )
                    }
                }
            }
        }
    }

    fun markLoginBrowserOpened() {
        _state.update { state ->
            state.copy(login = state.login.copy(browserOpened = true))
        }
    }

    fun reportLoginBrowserOpenFailure() {
        _state.update { state ->
            state.copy(
                login = state.login.copy(browserOpened = true),
                message = AppMessageUiState(error = uiText(R.string.error_open_browser_failed)),
            )
        }
    }

    fun cancelLogin() {
        loginAttemptId += 1
        loginStartJob?.cancel()
        loginStartJob = null
        loginPollingJob?.cancel()
        loginPollingJob = null
        _state.value = LoginScreenUiState(
            message = AppMessageUiState(error = uiText(R.string.error_login_cancelled)),
        )
    }

    private fun startLoginPolling(session: LoginSession) {
        loginPollingJob?.cancel()
        loginPollingJob = viewModelScope.launch {
            val startedAt = System.nanoTime()

            while (elapsedMillis(startedAt) < LOGIN_POLL_TIMEOUT_MS) {
                delay(LOGIN_POLL_INTERVAL_MS.milliseconds)
                if (state.value.login.session != session) {
                    return@launch
                }

                _state.update { state ->
                    state.copy(
                        login = state.login.copy(isPolling = true),
                        message = AppMessageUiState(status = uiText(R.string.status_waiting_browser_confirmation)),
                    )
                }

                when (val result = loginRepository.pollLogin(session)) {
                    LoginPollResult.Pending -> {
                        _state.update { state ->
                            state.copy(
                                login = state.login.copy(isPolling = true),
                                message = AppMessageUiState(status = uiText(R.string.status_login_not_confirmed_yet)),
                            )
                        }
                    }

                    is LoginPollResult.Failed -> {
                        if (result.isRecoverable) {
                            _state.update { state ->
                                state.copy(
                                    login = state.login.copy(isPolling = true),
                                    message = AppMessageUiState(
                                        status = loginPollRecoverableStatus(result.failure),
                                    ),
                                )
                            }
                        } else {
                            _state.update { state ->
                                state.copy(
                                    login = state.login.copy(isPolling = false),
                                    message = AppMessageUiState(error = result.failure.toUiText()),
                                )
                            }
                            loginPollingJob = null
                            return@launch
                        }
                    }

                    is LoginPollResult.Ready -> {
                        credentialsStore.save(result.credentials)
                        _state.value = LoginScreenUiState()
                        sessionStore.signIn(result.credentials)
                        loginPollingJob = null
                        return@launch
                    }
                }
            }

            if (state.value.login.session == session) {
                _state.update { state ->
                    state.copy(
                        login = state.login.copy(
                            session = null,
                            browserOpened = false,
                            isPolling = false,
                        ),
                        message = AppMessageUiState(error = uiText(R.string.error_login_confirmation_timeout)),
                    )
                }
            }
            loginPollingJob = null
        }
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }
}

private fun LoginPollFailure.toUiText() = when (this) {
    is LoginPollFailure.Http -> uiText(R.string.error_login_poll_http, code)
    LoginPollFailure.Network -> uiText(R.string.error_login_poll_network)
    LoginPollFailure.Unknown -> uiText(R.string.error_login_poll_unknown)
}

private fun loginPollRecoverableStatus(failure: LoginPollFailure) = when (failure) {
    LoginPollFailure.Network -> uiText(R.string.status_login_poll_network_retrying)
    else -> failure.toUiText()
}

private const val LOGIN_POLL_INTERVAL_MS = 2_000L
private const val LOGIN_POLL_TIMEOUT_MS = 120_000L
