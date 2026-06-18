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
                    statusMessage = uiText(R.string.status_loading_memories_timeline),
                    timelineLoadingDayIds = emptySet(),
                    timelineFailedDayIds = emptySet(),
                    timelineLoadMoreError = null,
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
                    errorMessage = uiText(R.string.error_enter_nextcloud_url),
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
                    statusMessage = uiText(R.string.status_creating_login_flow),
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
                        statusMessage = uiText(R.string.status_open_browser_confirm_login),
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
                            errorMessage = uiText(R.string.error_start_login_flow_failed),
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
                errorMessage = uiText(R.string.error_open_browser_failed),
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
                errorMessage = uiText(R.string.error_login_cancelled),
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
                        statusMessage = uiText(R.string.status_waiting_browser_confirmation),
                    )
                }

                when (val result = loginRepository.pollLogin(session)) {
                    LoginPollResult.Pending -> {
                        _state.update {
                            it.copy(
                                isLoginPolling = true,
                                statusMessage = uiText(R.string.status_login_not_confirmed_yet),
                            )
                        }
                    }

                    is LoginPollResult.Failed -> {
                        if (result.isRecoverable) {
                            _state.update {
                                it.copy(
                                    isLoginPolling = true,
                                    statusMessage = loginPollRecoverableStatus(result.failure),
                                )
                            }
                        } else {
                            _state.update {
                                it.copy(
                                    isLoginPolling = false,
                                    errorMessage = result.failure.toUiText(),
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
                                statusMessage = uiText(R.string.status_login_complete_loading_timeline),
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
                        errorMessage = uiText(R.string.error_login_confirmation_timeout),
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
                    statusMessage = uiText(R.string.status_loading_memories_api),
                )
            }

            runCatching { memoriesRepository.loadInitialTimeline(credentials) }
                .onSuccess { snapshot ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            timeline = snapshot,
                            timelineLoadingDayIds = emptySet(),
                            timelineFailedDayIds = emptySet(),
                            timelineLoadMoreError = null,
                            statusMessage = uiText(R.string.status_loaded_timeline_index, snapshot.totalMediaCountHint),
                        )
                    }
                    loadVisibleTimelineRange(
                        firstVisibleIndex = 0,
                        lastVisibleIndex = INITIAL_TIMELINE_PREFETCH_SLOTS,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            errorMessage = uiText(R.string.error_load_memories_api_failed),
                            statusMessage = null,
                        )
                    }
                }
        }
    }

    fun loadVisibleTimelineRange(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int,
    ) {
        val currentState = state.value
        val credentials = currentState.credentials ?: return
        val timeline = currentState.timeline ?: return
        if (timeline.slots.isEmpty()) {
            return
        }

        val windowStart = (firstVisibleIndex - TIMELINE_PREFETCH_SLOTS).coerceAtLeast(0)
        val windowEnd = (lastVisibleIndex + TIMELINE_PREFETCH_SLOTS).coerceAtMost(timeline.slots.lastIndex)
        if (windowStart > windowEnd) {
            return
        }

        val dayIds = timeline.slots
            .asSequence()
            .drop(windowStart)
            .take(windowEnd - windowStart + 1)
            .map { it.dayId }
            .distinct()
            .filterNot { it in timeline.loadedDayIds }
            .filterNot { it in currentState.timelineLoadingDayIds }
            .filterNot { it in currentState.timelineFailedDayIds }
            .take(TIMELINE_DAY_BATCH_SIZE)
            .toList()

        if (dayIds.isEmpty()) {
            return
        }

        _state.update {
            it.copy(
                timelineLoadingDayIds = it.timelineLoadingDayIds + dayIds,
                timelineLoadMoreError = null,
            )
        }

        viewModelScope.launch {
            runCatching { memoriesRepository.loadTimelineDays(credentials, dayIds) }
                .onSuccess { items ->
                    _state.update { state ->
                        val updatedTimeline = state.timeline?.mergeLoadedItems(
                            items = items,
                            loadedDayIds = dayIds.toSet(),
                        )

                        state.copy(
                            timeline = updatedTimeline,
                            timelineLoadingDayIds = state.timelineLoadingDayIds - dayIds.toSet(),
                            timelineFailedDayIds = state.timelineFailedDayIds - dayIds.toSet(),
                            timelineLoadMoreError = null,
                            statusMessage = uiText(
                                R.string.status_loaded_items,
                                updatedTimeline?.items?.size ?: state.timeline?.items?.size ?: 0,
                            ),
                        )
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.copy(
                            timelineLoadingDayIds = state.timelineLoadingDayIds - dayIds.toSet(),
                            timelineFailedDayIds = state.timelineFailedDayIds + dayIds,
                            timelineLoadMoreError = uiText(R.string.error_load_timeline_batch_failed),
                        )
                    }
                }
        }
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

data class MainUiState(
    val serverUrlInput: String = "",
    val credentials: AccountCredentials? = null,
    val loginSession: LoginSession? = null,
    val loginBrowserOpened: Boolean = false,
    val timeline: TimelineSnapshot? = null,
    val isBusy: Boolean = false,
    val isLoginPolling: Boolean = false,
    val timelineLoadingDayIds: Set<Int> = emptySet(),
    val timelineFailedDayIds: Set<Int> = emptySet(),
    val timelineLoadMoreError: UiText? = null,
    val statusMessage: UiText? = null,
    val errorMessage: UiText? = null,
)

private const val LOGIN_POLL_INTERVAL_MS = 2_000L
private const val LOGIN_POLL_TIMEOUT_MS = 120_000L
private const val INITIAL_TIMELINE_PREFETCH_SLOTS = 80
private const val TIMELINE_PREFETCH_SLOTS = 60
private const val TIMELINE_DAY_BATCH_SIZE = 4
