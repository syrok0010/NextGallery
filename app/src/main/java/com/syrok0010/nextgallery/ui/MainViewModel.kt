package com.syrok0010.nextgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.data.auth.LoginPollResult
import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel(
    private val credentialsStore: CredentialsStore,
    private val loginRepository: NextcloudLoginRepository,
    private val memoriesRepository: MemoriesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(MainUiState())
    val state: StateFlow<MainUiState> = _state.asStateFlow()
    private var loginStartJob: Job? = null
    private var loginPollingJob: Job? = null
    private var loginAttemptId = 0L

    init {
        val credentials = credentialsStore.load()
        if (credentials != null) {
            _state.update {
                it.copy(
                    credentials = credentials,
                    serverUrlInput = credentials.serverUrl,
                    statusMessage = "Загружаю Memories timeline",
                )
            }
            loadTimeline(credentials)
        }
    }

    fun updateServerUrl(value: String) {
        _state.update { it.copy(serverUrlInput = value) }
    }

    fun startLogin() {
        loginAttemptId += 1
        val attemptId = loginAttemptId
        loginStartJob?.cancel()
        loginPollingJob?.cancel()
        val serverUrl = state.value.serverUrlInput.trim()
        if (serverUrl.isBlank()) {
            _state.update {
                it.copy(
                    isBusy = false,
                    isLoginPolling = false,
                    loginSession = null,
                    loginBrowserOpened = false,
                    errorMessage = "Укажи адрес Nextcloud",
                    statusMessage = null,
                )
            }
            return
        }

        loginStartJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    isLoginPolling = false,
                    errorMessage = null,
                    statusMessage = "Создаю Login Flow",
                    loginSession = null,
                    loginBrowserOpened = false,
                )
            }

            try {
                val session = loginRepository.startLogin(serverUrl)
                if (attemptId != loginAttemptId) {
                    return@launch
                }

                loginStartJob = null
                _state.update {
                    it.copy(
                        isBusy = false,
                        isLoginPolling = true,
                        loginSession = session,
                        loginBrowserOpened = false,
                        statusMessage = "Открой браузер и подтверди вход",
                    )
                }
                startLoginPolling(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attemptId == loginAttemptId) {
                    loginStartJob = null
                    _state.update {
                        it.copy(
                            isBusy = false,
                            isLoginPolling = false,
                            errorMessage = error.message ?: "Не удалось начать Login Flow",
                            statusMessage = null,
                        )
                    }
                }
            }
        }
    }

    fun markLoginBrowserOpened() {
        _state.update { it.copy(loginBrowserOpened = true) }
    }

    fun reportLoginBrowserOpenFailure() {
        _state.update {
            it.copy(
                loginBrowserOpened = true,
                errorMessage = "Не удалось открыть браузер. Попробуй открыть вход вручную.",
            )
        }
    }

    fun cancelLogin() {
        loginAttemptId += 1
        loginStartJob?.cancel()
        loginStartJob = null
        loginPollingJob?.cancel()
        loginPollingJob = null
        _state.update {
            it.copy(
                loginSession = null,
                isBusy = false,
                isLoginPolling = false,
                loginBrowserOpened = false,
                statusMessage = null,
                errorMessage = "Вход отменен",
            )
        }
    }

    private fun startLoginPolling(session: LoginSession) {
        loginPollingJob?.cancel()
        loginPollingJob = viewModelScope.launch {
            val startedAt = System.nanoTime()

            while (elapsedMillis(startedAt) < LOGIN_POLL_TIMEOUT_MS) {
                delay(LOGIN_POLL_INTERVAL_MS)
                if (state.value.loginSession != session) {
                    return@launch
                }

                _state.update {
                    it.copy(
                        isLoginPolling = true,
                        errorMessage = null,
                        statusMessage = "Жду подтверждения входа в браузере",
                    )
                }

                when (val result = loginRepository.pollLogin(session)) {
                    LoginPollResult.Pending -> {
                        _state.update {
                            it.copy(
                                isLoginPolling = true,
                                statusMessage = "Вход еще не подтвержден в браузере",
                            )
                        }
                    }

                    is LoginPollResult.Failed -> {
                        if (result.isRecoverable) {
                            _state.update {
                                it.copy(
                                    isLoginPolling = true,
                                    statusMessage = "${result.message}. Повторю проверку автоматически.",
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    isLoginPolling = false,
                                    errorMessage = result.message,
                                    statusMessage = null,
                                )
                            }
                            loginPollingJob = null
                            return@launch
                        }
                    }

                    is LoginPollResult.Ready -> {
                        credentialsStore.save(result.credentials)
                        _state.update {
                            it.copy(
                                isBusy = false,
                                isLoginPolling = false,
                                credentials = result.credentials,
                                loginSession = null,
                                loginBrowserOpened = false,
                                statusMessage = "Вход выполнен, загружаю timeline",
                            )
                        }
                        loginPollingJob = null
                        loadTimeline(result.credentials)
                        return@launch
                    }
                }
            }

            if (state.value.loginSession == session) {
                _state.update {
                    it.copy(
                        isLoginPolling = false,
                        loginSession = null,
                        loginBrowserOpened = false,
                        errorMessage = "Не дождался подтверждения входа. Начни вход заново.",
                        statusMessage = null,
                    )
                }
            }
            loginPollingJob = null
        }
    }

    private fun elapsedMillis(startedAt: Long): Long {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
    }

    fun refresh() {
        val credentials = state.value.credentials ?: return
        loadTimeline(credentials)
    }

    fun logout() {
        loginAttemptId += 1
        loginStartJob?.cancel()
        loginStartJob = null
        loginPollingJob?.cancel()
        loginPollingJob = null
        credentialsStore.clear()
        _state.value = MainUiState()
    }

    private fun loadTimeline(credentials: AccountCredentials) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    statusMessage = "Загружаю Memories API",
                )
            }

            runCatching { memoriesRepository.loadInitialTimeline(credentials) }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            timeline = snapshot,
                            statusMessage = "Загружено ${snapshot.items.size} элементов",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Не удалось загрузить Memories API",
                            statusMessage = null,
                        )
                    }
                }
        }
    }
}

data class MainUiState(
    val serverUrlInput: String = "",
    val credentials: AccountCredentials? = null,
    val loginSession: LoginSession? = null,
    val loginBrowserOpened: Boolean = false,
    val timeline: TimelineSnapshot? = null,
    val isBusy: Boolean = false,
    val isLoginPolling: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)

private const val LOGIN_POLL_INTERVAL_MS = 2_000L
private const val LOGIN_POLL_TIMEOUT_MS = 120_000L
