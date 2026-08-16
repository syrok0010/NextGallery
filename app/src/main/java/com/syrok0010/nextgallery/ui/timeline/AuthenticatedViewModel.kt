package com.syrok0010.nextgallery.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionCoordinator
import com.syrok0010.nextgallery.data.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.data.local.LocalMediaSource
import com.syrok0010.nextgallery.data.local.LocalTimelineProjector
import com.syrok0010.nextgallery.ui.AppMessageUiState
import com.syrok0010.nextgallery.ui.SessionStore
import com.syrok0010.nextgallery.ui.SessionUiState
import com.syrok0010.nextgallery.ui.LocalMediaPrompt
import com.syrok0010.nextgallery.ui.LocalMediaUiState
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
    val localMedia: LocalMediaUiState = LocalMediaUiState(),
)

class AuthenticatedViewModel(
    private val sessionStore: SessionStore,
    private val credentialsStore: CredentialsStore,
    private val memoriesRepository: MemoriesRepository,
    private val localMediaPermissionCoordinator: LocalMediaPermissionCoordinator,
    private val localMediaSource: LocalMediaSource,
) : ViewModel() {
    private val _state = MutableStateFlow(AuthenticatedUiState())
    val state: StateFlow<AuthenticatedUiState> = _state.asStateFlow()
    private var remoteSnapshot: com.syrok0010.nextgallery.data.memories.TimelineSnapshot? = null
    private var localItems: List<com.syrok0010.nextgallery.data.memories.MediaItem> = emptyList()

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
                                snapshot = remoteSnapshot?.let(::projectTimeline) ?: transformed.snapshot,
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
                ): List<com.syrok0010.nextgallery.data.memories.MediaItem> {
                    val items = memoriesRepository.loadTimelineDays(credentials, dayIds)
                    remoteSnapshot = remoteSnapshot?.let { snapshot ->
                        com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler.mergeLoadedItems(
                            snapshot = snapshot,
                            items = items,
                            loadedDayIds = dayIds.toSet(),
                        )
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
        refreshLocalPermission()
    }

    fun showLocalMedia(requiresSettings: Boolean) {
        val mode = localMediaPermissionCoordinator.currentMode()
        when (mode) {
            LocalMediaPermissionMode.Full -> loadLocalMedia()
            LocalMediaPermissionMode.Partial -> updateLocalPrompt(LocalMediaPrompt.PartialRequiresFull, mode)
            LocalMediaPermissionMode.Denied -> updateLocalPrompt(
                if (requiresSettings) LocalMediaPrompt.OpenSettings else LocalMediaPrompt.Explanation,
                mode,
            )
        }
    }

    fun dismissLocalMediaPrompt() {
        updateLocalPrompt(LocalMediaPrompt.None)
    }

    fun onLocalMediaPermissionRequestCompleted() {
        localMediaPermissionCoordinator.markRequestCompleted()
        refreshLocalPermission()
    }

    fun refreshLocalPermission() {
        val mode = localMediaPermissionCoordinator.currentMode()
        when (mode) {
            LocalMediaPermissionMode.Full -> loadLocalMedia()
            LocalMediaPermissionMode.Partial -> {
                removeLocalMedia()
                updateLocalPrompt(LocalMediaPrompt.PartialRequiresFull, mode)
            }
            LocalMediaPermissionMode.Denied -> {
                removeLocalMedia()
                updateLocalPrompt(state.value.localMedia.prompt, mode)
            }
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

    private fun onSessionChanged(session: SessionUiState) {
        when (session) {
            SessionUiState.SignedOut -> {
                remoteSnapshot = null
                localItems = emptyList()
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
                val permissionMode = localMediaPermissionCoordinator.currentMode()
                val prompt = if (
                    permissionMode != LocalMediaPermissionMode.Full &&
                    localMediaPermissionCoordinator.shouldExplainAutomatically()
                ) {
                    localMediaPermissionCoordinator.markAutomaticExplanationShown()
                    LocalMediaPrompt.Explanation
                } else if (permissionMode == LocalMediaPermissionMode.Partial) {
                    LocalMediaPrompt.PartialRequiresFull
                } else {
                    LocalMediaPrompt.None
                }
                _state.update { it.copy(localMedia = LocalMediaUiState(permissionMode, prompt)) }
                if (permissionMode == LocalMediaPermissionMode.Full) {
                    loadLocalMedia()
                }
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
                    remoteSnapshot = cachedSnapshot
                    showedCachedTimeline = true
                    _state.update { state ->
                        state.copy(
                            timeline = TimelineUiState(snapshot = projectTimeline(cachedSnapshot)),
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
                    remoteSnapshot = snapshot
                    _state.update { state ->
                        state.copy(
                            credentials = credentials,
                            timeline = state.timeline.withRefreshedSnapshot(projectTimeline(snapshot)),
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
        viewModelScope.launch {
            _state.update { state ->
                state.copy(localMedia = state.localMedia.copy(
                    permissionMode = LocalMediaPermissionMode.Full,
                    prompt = LocalMediaPrompt.None,
                    isLoading = true,
                ))
            }
            runCatching { localMediaSource.readAll() }
                .onSuccess { items ->
                    localItems = items
                    _state.update { state ->
                        state.copy(
                            timeline = state.timeline.copy(snapshot = remoteSnapshot?.let(::projectTimeline)),
                            localMedia = state.localMedia.copy(isLoading = false, itemCount = items.size),
                        )
                    }
                }
                .onFailure {
                    _state.update { state ->
                        state.copy(
                            localMedia = state.localMedia.copy(isLoading = false),
                            message = AppMessageUiState(error = uiText(R.string.error_load_local_media_failed)),
                        )
                    }
                }
        }
    }

    private fun removeLocalMedia() {
        localItems = emptyList()
        _state.update { state ->
            state.copy(
                timeline = state.timeline.copy(snapshot = remoteSnapshot),
                localMedia = state.localMedia.copy(itemCount = 0, isLoading = false),
            )
        }
    }

    private fun projectTimeline(snapshot: com.syrok0010.nextgallery.data.memories.TimelineSnapshot) =
        LocalTimelineProjector.project(snapshot, localItems)

    private fun updateLocalPrompt(
        prompt: LocalMediaPrompt,
        mode: LocalMediaPermissionMode = state.value.localMedia.permissionMode,
    ) {
        _state.update { state ->
            state.copy(localMedia = state.localMedia.copy(permissionMode = mode, prompt = prompt))
        }
    }

    override fun onCleared() {
        timelineViewportController.cancel()
        super.onCleared()
    }
}
