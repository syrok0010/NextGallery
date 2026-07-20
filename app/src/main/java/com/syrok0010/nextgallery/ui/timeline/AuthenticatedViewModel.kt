package com.syrok0010.nextgallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.SessionStore
import com.syrok0010.nextgallery.ui.SessionUiState
import com.syrok0010.nextgallery.ui.TimelineUiState
import com.syrok0010.nextgallery.ui.uiText
import com.syrok0010.nextgallery.ui.withRefreshedSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthenticatedUiState(
    val credentials: AccountCredentials? = null,
    val timeline: TimelineUiState = TimelineUiState(),
    val isBusy: Boolean = false,
    val message: AppMessageUiState = AppMessageUiState(),
)

class AuthenticatedViewModel(
    private val sessionStore: SessionStore,
    private val credentialsStore: CredentialsStore,
    private val memoriesRepository: MemoriesRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthenticatedUiState())
    val state: StateFlow<AuthenticatedUiState> = _state.asStateFlow()

    private val timelineViewportController: TimelineViewportController =
        DefaultTimelineViewportController(
            scope = viewModelScope,
            host = object : TimelineViewportHost {
                override fun currentSession(): TimelineViewportSession? {
                    val credentials = state.value.credentials ?: return null
                    return TimelineViewportSession(
                        credentials = credentials,
                        timelineState = state.value.timeline,
                    )
                }

                override fun updateTimeline(transform: (TimelineUiState) -> TimelineUiState) {
                    _state.update { state ->
                        state.copy(timeline = transform(state.timeline))
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

    init {
        sessionStore.session
            .onEach(::onSessionChanged)
            .launchIn(viewModelScope)
    }

    fun refresh() {
        state.value.credentials?.let(::loadTimeline)
    }

    fun logout() {
        credentialsStore.clear()
        viewModelScope.launch {
            memoriesRepository.clearCache()
        }
        sessionStore.signOut()
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

    private fun onSessionChanged(session: SessionUiState) {
        when (session) {
            SessionUiState.SignedOut -> {
                _state.value = AuthenticatedUiState()
            }

            is SessionUiState.SignedIn -> {
                if (_state.value.credentials == session.credentials) {
                    return
                }

                _state.value = AuthenticatedUiState(
                    credentials = session.credentials,
                    message = AppMessageUiState(status = uiText(R.string.status_loading_memories_timeline)),
                )
                loadTimeline(session.credentials)
            }
        }
    }

    private fun loadTimeline(credentials: AccountCredentials) {
        viewModelScope.launch {
            _state.update { state ->
                state.copy(
                    isBusy = true,
                    message = AppMessageUiState(status = uiText(R.string.status_loading_memories_api)),
                )
            }

            var showedCachedTimeline = false
            val canShowCachedTimeline = state.value.credentials == credentials && state.value.timeline.snapshot == null
            if (canShowCachedTimeline) {
                memoriesRepository.loadCachedTimeline(credentials)?.let { cachedSnapshot ->
                    showedCachedTimeline = true
                    _state.update { state ->
                        state.copy(
                            timeline = TimelineUiState(snapshot = cachedSnapshot),
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
                        state.copy(
                            credentials = credentials,
                            timeline = state.timeline.withRefreshedSnapshot(snapshot),
                            isBusy = false,
                            message = AppMessageUiState(
                                status = uiText(R.string.status_loaded_timeline_index, snapshot.totalMediaCountHint),
                            ),
                        )
                    }
                    if (!showedCachedTimeline) {
                        timelineViewportController.prefetchFromStart()
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.copy(
                            isBusy = false,
                            message = AppMessageUiState(error = uiText(R.string.error_load_memories_api_failed)),
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        timelineViewportController.cancel()
        super.onCleared()
    }
}
