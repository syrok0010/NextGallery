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
                    loadVisibleTimelineRange(
                        firstVisibleIndex = 0,
                        lastVisibleIndex = INITIAL_TIMELINE_PREFETCH_SLOTS,
                    )
                }
                .onFailure { error ->
                    _state.update {
                        it.copy(
                            isBusy = false,
                            message = AppMessageUiState(error = uiText(R.string.error_load_memories_api_failed)),
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
        val signedIn = currentState.signedIn ?: return
        val credentials = signedIn.credentials
        val timelineState = signedIn.timeline
        val timeline = timelineState.snapshot ?: return
        if (timeline.slots.isEmpty()) {
            return
        }

        val windowStart = (firstVisibleIndex - TIMELINE_PREFETCH_SLOTS).coerceAtLeast(0)
        val windowEnd = (lastVisibleIndex + TIMELINE_PREFETCH_SLOTS).coerceAtMost(timeline.slots.lastIndex)
        if (windowStart > windowEnd) {
            return
        }

        loadVisibleThumbnails(
            credentials = credentials,
            timelineState = timelineState,
            windowStart = windowStart,
            windowEnd = windowEnd,
        )

        val dayIds = timeline.slots
            .asSequence()
            .drop(windowStart)
            .take(windowEnd - windowStart + 1)
            .map { it.dayId }
            .distinct()
            .filterNot { it in timeline.loadedDayIds }
            .filterNot { it in timelineState.loadingDayIds }
            .filterNot { it in timelineState.failedDayIds }
            .take(TIMELINE_DAY_BATCH_SIZE)
            .toList()

        if (dayIds.isEmpty()) {
            return
        }

        _state.update { state ->
            state.updateTimeline {
                it.copy(
                    loadingDayIds = it.loadingDayIds + dayIds,
                    loadMoreError = null,
                )
            }
        }

        viewModelScope.launch {
            runCatching { memoriesRepository.loadTimelineDays(credentials, dayIds) }
                .onSuccess { items ->
                    _state.update { state ->
                        val currentTimeline = state.signedIn?.timeline?.snapshot
                        val updatedTimeline = currentTimeline?.mergeLoadedItems(
                            items = items,
                            loadedDayIds = dayIds.toSet(),
                        )

                        state.updateTimeline {
                            it.copy(
                                snapshot = updatedTimeline,
                                loadingDayIds = it.loadingDayIds - dayIds.toSet(),
                                failedDayIds = it.failedDayIds - dayIds.toSet(),
                                loadMoreError = null,
                            )
                        }.copy(
                            message = AppMessageUiState(status = uiText(
                                R.string.status_loaded_items,
                                updatedTimeline?.items?.size ?: currentTimeline?.items?.size ?: 0,
                            )),
                        )
                    }
                    val updatedTimelineState = state.value.signedIn?.timeline
                    if (updatedTimelineState != null) {
                        loadVisibleThumbnails(
                            credentials = credentials,
                            timelineState = updatedTimelineState,
                            windowStart = windowStart,
                            windowEnd = windowEnd,
                        )
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.updateTimeline {
                            it.copy(
                                loadingDayIds = it.loadingDayIds - dayIds.toSet(),
                                failedDayIds = it.failedDayIds + dayIds,
                                loadMoreError = uiText(R.string.error_load_timeline_batch_failed),
                            )
                        }
                    }
                }
        }
    }

    private fun loadVisibleThumbnails(
        credentials: AccountCredentials,
        timelineState: TimelineUiState,
        windowStart: Int,
        windowEnd: Int,
    ) {
        val timeline = timelineState.snapshot ?: return
        if (windowStart > windowEnd) {
            return
        }

        val fileIds = timeline.slots
            .asSequence()
            .drop(windowStart)
            .take(windowEnd - windowStart + 1)
            .mapNotNull { it.mediaItem?.fileId }
            .distinct()
            .filterNot { it in timelineState.thumbnailPreviews }
            .filterNot { it in timelineState.thumbnailLoadingFileIds }
            .filterNot { it in timelineState.thumbnailFailedFileIds }
            .take(TIMELINE_THUMBNAIL_BATCH_SIZE)
            .toList()

        if (fileIds.isEmpty()) {
            return
        }

        _state.update { state ->
            state.updateTimeline {
                it.copy(thumbnailLoadingFileIds = it.thumbnailLoadingFileIds + fileIds)
            }
        }

        viewModelScope.launch {
            runCatching { memoriesRepository.loadThumbnails(credentials, fileIds) }
                .onSuccess { previews ->
                    val previewsByFileId = previews.associateBy { it.fileId }
                    val missingFileIds = fileIds.filterNot { it in previewsByFileId }

                    _state.update { state ->
                        state.updateTimeline {
                            it.copy(
                                thumbnailPreviews = it.thumbnailPreviews + previewsByFileId,
                                thumbnailLoadingFileIds = it.thumbnailLoadingFileIds - fileIds.toSet(),
                                thumbnailFailedFileIds = it.thumbnailFailedFileIds + missingFileIds,
                            )
                        }
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.updateTimeline {
                            it.copy(
                                thumbnailLoadingFileIds = it.thumbnailLoadingFileIds - fileIds.toSet(),
                                thumbnailFailedFileIds = it.thumbnailFailedFileIds + fileIds,
                            )
                        }
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

private const val LOGIN_POLL_INTERVAL_MS = 2_000L
private const val LOGIN_POLL_TIMEOUT_MS = 120_000L
private const val INITIAL_TIMELINE_PREFETCH_SLOTS = 80
private const val TIMELINE_PREFETCH_SLOTS = 60
private const val TIMELINE_DAY_BATCH_SIZE = 4
private const val TIMELINE_THUMBNAIL_BATCH_SIZE = 64
