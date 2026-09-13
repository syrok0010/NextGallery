package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** The authenticated library survives screen changes; logout cancels its workflow. */
internal class TimelineRepository(
    sessions: SessionStore,
    source: RemoteTimelineSource,
    localSource: LocalMediaSource,
    permissions: StateFlow<LocalMediaPermissionMode?>,
    scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(TimelineScreenState())
    val state = mutableState.asStateFlow()
    private var workflow: TimelineWorkflow? = null
    init {
        scope.launch {
            sessions.session.collectLatest { session ->
                workflow = null
                mutableState.value = TimelineScreenState()
                if (session is SessionUiState.SignedIn) coroutineScope {
                    val timeline = TimelineWorkflow(session.credentials, source, localSource::updates, this)
                    workflow = timeline
                    launch { permissions.collect { it?.let(timeline::updateLocalAccess) } }
                    timeline.state.collect { mutableState.value = it.toUiState() }
                }
            }
        }
    }
    fun refresh() { workflow?.refresh() }
    fun observeViewport(observation: TimelineViewportObservation) { workflow?.observeViewport(observation) }
}
