package com.syrok0010.nextgallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.data.local.LocalMediaSource
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.data.memories.UnifiedTimelineProjection
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.SessionStore
import com.syrok0010.nextgallery.ui.SessionUiState
import com.syrok0010.nextgallery.ui.TimelineUiState
import com.syrok0010.nextgallery.ui.uiText
import com.syrok0010.nextgallery.ui.withRefreshedSnapshot
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthenticatedUiState(
    val credentials: AccountCredentials? = null,
    val timeline: TimelineUiState = TimelineUiState(),
    val isBusy: Boolean = false,
    val message: AppMessageUiState = AppMessageUiState(),
    val localMediaPermissionMode: LocalMediaPermissionMode? = null,
)

class AuthenticatedViewModel(
    private val sessionStore: SessionStore,
    private val credentialsStore: CredentialsStore,
    private val memoriesRepository: MemoriesRepository,
    private val localMediaSource: LocalMediaSource,
    private val unifiedTimelineProjection: UnifiedTimelineProjection,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthenticatedUiState())
    val state: StateFlow<AuthenticatedUiState> = _state.asStateFlow()
    private val localReconcileRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var localMediaJob: Job? = null

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
                        val transformed = transform(state.timeline)
                        state.copy(
                            timeline = transformed.copy(
                                snapshot = unifiedTimelineProjection.snapshot ?: transformed.snapshot,
                            ),
                        )
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
                ): List<MediaItem> {
                    val items = memoriesRepository.loadTimelineDays(credentials, dayIds)
                    val projectedSnapshot = unifiedTimelineProjection.mergeRemoteItems(
                        items = items,
                        loadedDayIds = dayIds.toSet(),
                    )
                    _state.update { state ->
                        state.copy(timeline = state.timeline.copy(snapshot = projectedSnapshot.snapshot))
                    }
                    return items
                }
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

    fun onLocalMediaPermissionChanged(mode: LocalMediaPermissionMode) {
        when (mode) {
            LocalMediaPermissionMode.Full -> loadLocalMedia()
            LocalMediaPermissionMode.Partial,
            LocalMediaPermissionMode.Denied,
            -> removeLocalMedia(mode)
        }
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

    private suspend fun onSessionChanged(session: SessionUiState) {
        when (session) {
            SessionUiState.SignedOut -> {
                stopLocalMedia()
                unifiedTimelineProjection.clear()
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
                    val projectedSnapshot = unifiedTimelineProjection
                        .replaceRemoteSnapshot(cachedSnapshot)
                        .snapshot
                    _state.update { state ->
                        state.copy(
                            timeline = TimelineUiState(snapshot = projectedSnapshot),
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
                    val projectedSnapshot = unifiedTimelineProjection
                        .replaceRemoteSnapshot(snapshot)
                        .snapshot
                    _state.update { state ->
                        state.copy(
                            credentials = credentials,
                            timeline = state.timeline.withRefreshedSnapshot(projectedSnapshot ?: snapshot),
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

    private fun loadLocalMedia() {
        _state.update { state ->
            state.copy(localMediaPermissionMode = LocalMediaPermissionMode.Full)
        }
        if (localMediaJob?.isActive == true) {
            localReconcileRequests.tryEmit(Unit)
            return
        }
        localMediaJob = localMediaSource.updates(localReconcileRequests)
            .onEach { indexState ->
                val projectedSnapshot = unifiedTimelineProjection
                    .replaceLocalItems(indexState.items)
                    .snapshot
                _state.update { state ->
                    state.copy(
                        timeline = state.timeline.copy(snapshot = projectedSnapshot),
                        message = AppMessageUiState(
                            status = indexState.progress?.let { progress ->
                                uiText(
                                    R.string.status_indexing_local_media,
                                    progress.indexedCount,
                                    progress.totalCount,
                                )
                            } ?: uiText(R.string.status_loaded_items, indexState.items.size),
                        ),
                    )
                }
            }
            .catch {
                _state.update { state ->
                    state.copy(
                        message = AppMessageUiState(error = uiText(R.string.error_load_local_media_failed)),
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    private fun removeLocalMedia(permissionMode: LocalMediaPermissionMode) {
        stopLocalMedia()
        viewModelScope.launch {
            val projectedSnapshot = unifiedTimelineProjection
                .replaceLocalItems(emptyList())
                .snapshot
            _state.update { state ->
                state.copy(
                    timeline = state.timeline.copy(snapshot = projectedSnapshot),
                    localMediaPermissionMode = permissionMode,
                )
            }
        }
    }

    private fun stopLocalMedia() {
        localMediaJob?.cancel()
        localMediaJob = null
    }

    override fun onCleared() {
        timelineViewportController.cancel()
        super.onCleared()
    }
}
