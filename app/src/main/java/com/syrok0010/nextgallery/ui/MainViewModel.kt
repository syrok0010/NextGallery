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
        val serverUrl = state.value.serverUrlInput.trim()
        if (serverUrl.isBlank()) {
            _state.update { it.copy(errorMessage = "Укажи адрес Nextcloud") }
            return
        }

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    statusMessage = "Создаю Login Flow",
                )
            }

            runCatching { loginRepository.startLogin(serverUrl) }
                .onSuccess { session ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            loginSession = session,
                            statusMessage = "Открой браузер и подтверди вход",
                        )
                    }
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = error.message ?: "Не удалось начать Login Flow",
                            statusMessage = null,
                        )
                    }
                }
        }
    }

    fun pollLogin() {
        val session = state.value.loginSession ?: return

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    errorMessage = null,
                    statusMessage = "Проверяю подтверждение входа",
                )
            }

            when (val result = loginRepository.pollLogin(session)) {
                LoginPollResult.Pending -> {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            statusMessage = "Вход еще не подтвержден в браузере",
                        )
                    }
                }

                is LoginPollResult.Failed -> {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = result.message,
                            statusMessage = null,
                        )
                    }
                }

                is LoginPollResult.Ready -> {
                    credentialsStore.save(result.credentials)
                    _state.update {
                        it.copy(
                            isBusy = false,
                            credentials = result.credentials,
                            loginSession = null,
                            statusMessage = "Вход выполнен, загружаю timeline",
                        )
                    }
                    loadTimeline(result.credentials)
                }
            }
        }
    }

    fun refresh() {
        val credentials = state.value.credentials ?: return
        loadTimeline(credentials)
    }

    fun logout() {
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
    val timeline: TimelineSnapshot? = null,
    val isBusy: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
)
