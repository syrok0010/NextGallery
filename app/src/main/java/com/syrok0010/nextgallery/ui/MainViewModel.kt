package com.syrok0010.nextgallery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.auth.LoginPollFailure
import com.syrok0010.nextgallery.data.auth.LoginPollResult
import com.syrok0010.nextgallery.data.auth.LoginSession
import com.syrok0010.nextgallery.data.auth.NextcloudLoginRepository
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.ui.timeline.DefaultTimelineViewportController
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportController
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportHost
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportLoadingMode
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportObservation
import com.syrok0010.nextgallery.ui.timeline.TimelineViewportSession
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
    private val timelineViewportController: TimelineViewportController =
        DefaultTimelineViewportController(
            scope = viewModelScope,
            host = object : TimelineViewportHost {
                override fun currentSession(): TimelineViewportSession? {
                    val signedIn = state.value.signedIn ?: return null
                    return TimelineViewportSession(
                        credentials = signedIn.credentials,
                        timelineState = signedIn.timeline,
                    )
                }

                override fun updateTimeline(transform: (TimelineUiState) -> TimelineUiState) {
                    _state.update { state ->
                        state.updateTimeline(transform)
                    }
                }

                override fun showLoadedItemsStatus(itemCount: Int) {
                    _state.update { state ->
                        state.copy(
                            message = AppMessageUiState(
                                status = uiText(R.string.status_loaded_items, itemCount),
                            ),
                        )
                    }
                }

                override suspend fun loadTimelineDays(
                    credentials: AccountCredentials,
                    dayIds: List<Int>,
                ) = memoriesRepository.loadTimelineDays(credentials, dayIds)

                override suspend fun loadThumbnails(
                    credentials: AccountCredentials,
                    fileIds: List<Long>,
                    etagsByFileId: Map<Long, String?>,
                ) = memoriesRepository.loadThumbnails(credentials, fileIds, etagsByFileId)
            },
        )
    private var loginStartJob: Job? = null
    private var loginPollingJob: Job? = null
    private var loginAttemptId = 0L

    init {
        val credentials = credentialsStore.load()
        if (credentials != null) {
            _state.update {
                it.copy(
                    session = SessionUiState.SignedIn(
                        credentials = credentials,
                        timeline = TimelineUiState(),
                    ),
                    message = AppMessageUiState(status = uiText(R.string.status_loading_memories_timeline)),
                )
            }
            loadTimeline(credentials)
        }
    }

    fun updateServerUrl(value: String) {
        _state.update { state ->
            state.updateLogin { it.copy(serverUrlInput = value) }
        }
    }

    fun startLogin() {
        loginAttemptId += 1
        val attemptId = loginAttemptId
        loginStartJob?.cancel()
        loginPollingJob?.cancel()
        val serverUrl = state.value.loginState?.serverUrlInput?.trim() ?: return
        if (serverUrl.isBlank()) {
            _state.update { state ->
                state.updateLogin {
                    it.copy(
                        session = null,
                        browserOpened = false,
                        isPolling = false,
                    )
                }.copy(
                    isBusy = false,
                    message = AppMessageUiState(error = uiText(R.string.error_enter_nextcloud_url)),
                )
            }
            return
        }

        loginStartJob = viewModelScope.launch {
            _state.update { state ->
                state.updateLogin {
                    it.copy(
                        session = null,
                        browserOpened = false,
                        isPolling = false,
                    )
                }.copy(
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
                    state.updateLogin {
                        it.copy(
                            session = session,
                            browserOpened = false,
                            isPolling = true,
                        )
                    }.copy(
                        isBusy = false,
                        message = AppMessageUiState(status = uiText(R.string.status_open_browser_confirm_login)),
                    )
                }
                startLoginPolling(session)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (attemptId == loginAttemptId) {
                    loginStartJob = null
                    _state.update { state ->
                        state.updateLogin { it.copy(isPolling = false) }.copy(
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
            state.updateLogin { it.copy(browserOpened = true) }
        }
    }

    fun reportLoginBrowserOpenFailure() {
        _state.update { state ->
            state.updateLogin {
                it.copy(browserOpened = true)
            }.copy(
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
        _state.update { state ->
            state.updateLogin {
                it.copy(
                    session = null,
                    browserOpened = false,
                    isPolling = false,
                )
            }.copy(
                isBusy = false,
                message = AppMessageUiState(error = uiText(R.string.error_login_cancelled)),
            )
        }
    }

    private fun startLoginPolling(session: LoginSession) {
        loginPollingJob?.cancel()
        loginPollingJob = viewModelScope.launch {
            val startedAt = System.nanoTime()

            while (elapsedMillis(startedAt) < LOGIN_POLL_TIMEOUT_MS) {
                delay(LOGIN_POLL_INTERVAL_MS)
                if (state.value.loginState?.session != session) {
                    return@launch
                }

                _state.update { state ->
                    state.updateLogin {
                        it.copy(isPolling = true)
                    }.copy(
                        message = AppMessageUiState(status = uiText(R.string.status_waiting_browser_confirmation)),
                    )
                }

                when (val result = loginRepository.pollLogin(session)) {
                    LoginPollResult.Pending -> {
                        _state.update { state ->
                            state.updateLogin {
                                it.copy(isPolling = true)
                            }.copy(
                                message = AppMessageUiState(status = uiText(R.string.status_login_not_confirmed_yet)),
                            )
                        }
                    }

                    is LoginPollResult.Failed -> {
                        if (result.isRecoverable) {
                            _state.update { state ->
                                state.updateLogin {
                                    it.copy(isPolling = true)
                                }.copy(
                                    message = AppMessageUiState(status = loginPollRecoverableStatus(result.failure)),
                                )
                            }
                        } else {
                            _state.update { state ->
                                state.updateLogin {
                                    it.copy(isPolling = false)
                                }.copy(
                                    message = AppMessageUiState(error = result.failure.toUiText()),
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
                                session = SessionUiState.SignedIn(
                                    credentials = result.credentials,
                                    timeline = TimelineUiState(),
                                ),
                                message = AppMessageUiState(
                                    status = uiText(R.string.status_login_complete_loading_timeline),
                                ),
                            )
                        }
                        loginPollingJob = null
                        loadTimeline(result.credentials)
                        return@launch
                    }
                }
            }

            if (state.value.loginState?.session == session) {
                _state.update { state ->
                    state.updateLogin {
                        it.copy(
                            session = null,
                            browserOpened = false,
                            isPolling = false,
                        )
                    }.copy(
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

    fun refresh() {
        val credentials = state.value.signedIn?.credentials ?: return
        loadTimeline(credentials)
    }

    fun logout() {
        loginAttemptId += 1
        loginStartJob?.cancel()
        loginStartJob = null
        loginPollingJob?.cancel()
        loginPollingJob = null
        credentialsStore.clear()
        viewModelScope.launch {
            memoriesRepository.clearCache()
        }
        _state.value = MainUiState()
    }

    private fun loadTimeline(credentials: AccountCredentials) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isBusy = true,
                    message = AppMessageUiState(status = uiText(R.string.status_loading_memories_api)),
                )
            }

            val canShowCachedTimeline = state.value.signedIn?.timeline?.snapshot == null
            if (canShowCachedTimeline) {
                memoriesRepository.loadCachedTimeline(credentials)?.let { cachedSnapshot ->
                    _state.update { state ->
                        state.updateTimeline {
                            TimelineUiState(snapshot = cachedSnapshot)
                        }.copy(
                            message = AppMessageUiState(
                                status = uiText(
                                    R.string.status_loaded_timeline_index,
                                    cachedSnapshot.totalMediaCountHint,
                                ),
                            ),
                        )
                    }
                    timelineViewportController.prefetchFromStart()
                }
            }

            runCatching { memoriesRepository.loadInitialTimeline(credentials) }
                .onSuccess { snapshot ->
                    _state.update { state ->
                        state.updateTimeline {
                            TimelineUiState(snapshot = snapshot)
                        }.copy(
                            isBusy = false,
                            message = AppMessageUiState(
                                status = uiText(R.string.status_loaded_timeline_index, snapshot.totalMediaCountHint),
                            ),
                        )
                    }
                    timelineViewportController.prefetchFromStart()
                }
                .onFailure {
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = AppMessageUiState(error = uiText(R.string.error_load_memories_api_failed)),
                        )
                    }
                }
        }
    }

    internal fun observeTimelineViewport(observation: TimelineViewportObservation) {
        timelineViewportController.onViewportObservation(observation)
    }

    fun loadVisibleTimelineRange(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
    ) {
        observeTimelineViewport(
            TimelineViewportObservation(
                firstVisibleSlotIndex = firstVisibleIndex,
                lastVisibleSlotIndex = lastVisibleIndex,
                loadingMode = TimelineViewportLoadingMode.Immediate,
            ),
        )
    }

    override fun onCleared() {
        timelineViewportController.cancel()
        super.onCleared()
    }
}

private fun LoginPollFailure.toUiText(): UiText {
    return when (this) {
        is LoginPollFailure.Http -> uiText(R.string.error_login_poll_http, code)
        LoginPollFailure.Network -> uiText(R.string.error_login_poll_network)
        LoginPollFailure.Unknown -> uiText(R.string.error_login_poll_unknown)
    }
}

private fun loginPollRecoverableStatus(failure: LoginPollFailure): UiText {
    return when (failure) {
        LoginPollFailure.Network -> uiText(R.string.status_login_poll_network_retrying)
        else -> failure.toUiText()
    }
}

private const val LOGIN_POLL_INTERVAL_MS = 2_000L
private const val LOGIN_POLL_TIMEOUT_MS = 120_000L
