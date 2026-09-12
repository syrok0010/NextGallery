package com.syrok0010.nextgallery.feature.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.AppMessageUiState
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

data class AuthenticatedUiState(
    val isSignedIn: Boolean = false,
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
) : ViewModel() {
    private val mutableState = MutableStateFlow(AuthenticatedUiState())
    val state = mutableState.asStateFlow()
    private var workflow: TimelineWorkflow? = null

    init {
        viewModelScope.launch {
            sessionStore.session.collectLatest { session ->
                workflow = null
                mutableState.value = AuthenticatedUiState()
                if (session is SessionUiState.SignedIn) coroutineScope {
                    val timeline = TimelineWorkflow(session.credentials, memoriesRepository, localMediaSource::updates, this)
                    workflow = timeline
                    timeline.state.collect { mutableState.value = it.toUiState() }
                }
            }
        }
    }

    fun refresh() { workflow?.refresh() }
    fun onLocalMediaPermissionChanged(mode: LocalMediaPermissionMode) { workflow?.updateLocalAccess(mode) }
    internal fun observeTimelineViewport(observation: TimelineViewportObservation) { workflow?.observeViewport(observation) }
    fun loadVisibleTimelineRange(firstVisibleIndex: Int, lastVisibleIndex: Int) {
        observeTimelineViewport(TimelineViewportObservation(firstVisibleIndex, lastVisibleIndex, TimelineViewportLoadingMode.Immediate))
    }
    fun logout() {
        credentialsStore.clear()
        viewModelScope.launch { memoriesRepository.clearCache() }
        sessionStore.signOut()
    }
}

private fun TimelineWorkflowState.toUiState(): AuthenticatedUiState {
    // Independent source states survive each other's updates; errors take precedence over progress.
    val error = when {
        remote == TimelineOperation.Failed -> uiText(R.string.error_load_memories_api_failed)
        local == TimelineOperation.Failed -> uiText(R.string.error_load_local_media_failed)
        else -> null
    }
    val status = when {
        remote == TimelineOperation.Loading -> uiText(R.string.status_loading_memories_api)
        local is TimelineOperation.Indexing -> uiText(R.string.status_indexing_local_media, local.indexed, local.total)
        else -> uiText(R.string.status_loaded_items, snapshot?.items?.size ?: 0)
    }
    return AuthenticatedUiState(
        isSignedIn = true,
        timeline = TimelineUiState(snapshot, loadingDayIds, failedDayIds,
            if (failedDayIds.isEmpty()) null else uiText(R.string.error_load_timeline_batch_failed)),
        isBusy = remote == TimelineOperation.Loading,
        message = AppMessageUiState(status = status, error = error),
        localMediaPermissionMode = permission,
    )
}
