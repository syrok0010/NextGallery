package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.LocalMediaProjection
import com.syrok0010.nextgallery.core.media.RemoteMediaProjection
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaIndexState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface TimelineOperation {
    data object Idle : TimelineOperation
    data object Loading : TimelineOperation
    data object Failed : TimelineOperation
    data class Indexing(val indexed: Int, val total: Int) : TimelineOperation
}

internal data class TimelineWorkflowState(
    val snapshot: TimelineSnapshot? = null,
    val loadingDayIds: Set<Int> = emptySet(),
    val failedDayIds: Set<Int> = emptySet(),
    val remote: TimelineOperation = TimelineOperation.Idle,
    val local: TimelineOperation = TimelineOperation.Idle,
    val permission: LocalMediaPermissionMode? = null,
)

/** One session owns all timeline mutations. Intents and state collection use the Main scope. */
internal class TimelineWorkflow(
    private val credentials: AccountCredentials,
    private val source: RemoteTimelineSource,
    private val localUpdates: (Flow<Unit>) -> Flow<LocalMediaIndexState>,
    private val scope: CoroutineScope,
    private val projection: UnifiedTimelineProjection = UnifiedTimelineProjection(),
    private val debounceMillis: Long = 450,
) {
    private val mutableState = MutableStateFlow(TimelineWorkflowState())
    val state = mutableState.asStateFlow()
    private val localRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var localJob: Job? = null
    private var refreshJob: Job? = null
    private var hydrationJob: Job? = null
    private var viewportJob: Job? = null
    private var viewport: TimelineViewportObservation? = null
    private var generation = 0L

    init { refresh() }

    fun refresh() {
        val currentGeneration = ++generation
        refreshJob?.cancel()
        hydrationJob?.cancel()
        mutableState.update { it.copy(remote = TimelineOperation.Loading, loadingDayIds = emptySet(), failedDayIds = emptySet()) }
        refreshJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (state.value.snapshot == null) {
                    source.loadCachedTimeline(credentials)?.let { cached ->
                        if (currentGeneration != generation) return@launch
                        val result = projection.replaceRemoteSnapshot(cached)
                        mutableState.update { it.copy(snapshot = result.snapshot) }
                    }
                }
                val remote = source.loadInitialTimeline(credentials)
                if (currentGeneration != generation) return@launch
                val result = projection.replaceRemoteSnapshot(remote)
                mutableState.update { it.copy(snapshot = result.snapshot, remote = TimelineOperation.Idle) }
                if (viewport == null) viewport = TimelineViewportObservation(0, 11, TimelineViewportLoadingMode.Immediate)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentGeneration == generation) {
                    mutableState.update { it.copy(remote = TimelineOperation.Failed) }
                }
            } finally {
                if (currentGeneration == generation) {
                    refreshJob = null
                    hydrateViewport()
                }
            }
        }
        refreshJob?.start()
    }

    fun observeViewport(observation: TimelineViewportObservation) {
        viewportJob?.cancel()
        if (observation.loadingMode == TimelineViewportLoadingMode.Debounced) {
            viewport = null
            viewportJob = scope.launch {
                delay(debounceMillis)
                viewport = observation
                hydrateViewport()
            }
        } else {
            viewport = observation
            hydrateViewport()
        }
    }

    private fun hydrateViewport() {
        if (refreshJob?.isActive == true || hydrationJob?.isActive == true) return
        val currentGeneration = generation
        hydrationJob = scope.launch(start = CoroutineStart.LAZY) {
            while (currentGeneration == generation) {
                val observation = viewport ?: break
                val snapshot = state.value.snapshot ?: break
                val dayIds = daysForViewport(snapshot, observation, state.value.failedDayIds)
                if (dayIds.isEmpty()) break
                mutableState.update { it.copy(loadingDayIds = dayIds.toSet()) }
                try {
                    val items = source.loadTimelineDays(credentials, dayIds)
                    if (currentGeneration != generation) return@launch
                    val result = projection.mergeRemoteItems(RemoteMediaProjection(items), dayIds.toSet())
                    mutableState.update { it.copy(snapshot = result.snapshot, loadingDayIds = emptySet()) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (currentGeneration == generation) {
                        mutableState.update { it.copy(loadingDayIds = emptySet(), failedDayIds = it.failedDayIds + dayIds) }
                    }
                }
            }
        }
        hydrationJob?.start()
    }

    fun updateLocalAccess(mode: LocalMediaPermissionMode) {
        val previousMode = state.value.permission
        mutableState.update { it.copy(permission = mode) }
        if (mode != LocalMediaPermissionMode.Full) {
            localJob?.cancel()
            localJob = scope.launch {
                val result = projection.replaceLocalItems(LocalMediaProjection(emptyList()))
                mutableState.update { it.copy(snapshot = result.snapshot, local = TimelineOperation.Idle) }
            }
            return
        }
        if (previousMode == LocalMediaPermissionMode.Full && localJob?.isActive == true) {
            localRequests.tryEmit(Unit)
            return
        }
        localJob?.cancel()
        var previousItems: List<MediaItem>? = null
        localJob = localUpdates(localRequests).onEach { update ->
            if (previousItems !== update.items) {
                projection.replaceLocalItems(LocalMediaProjection(update.items))
                previousItems = update.items
            }
            val localOperation = when {
                update.failure -> TimelineOperation.Failed
                update.progress != null -> TimelineOperation.Indexing(
                    update.progress.indexedCount,
                    update.progress.totalCount,
                )
                else -> TimelineOperation.Idle
            }
            mutableState.update { it.copy(snapshot = projection.snapshot, local = localOperation) }
        }.catch {
            mutableState.update { it.copy(local = TimelineOperation.Failed) }
        }.launchIn(scope)
    }
}

/** Pure viewport policy; data publication remains entirely inside TimelineWorkflow. */
internal fun daysForViewport(
    snapshot: TimelineSnapshot,
    observation: TimelineViewportObservation,
    failedDays: Set<Int>,
): List<Int> {
    val start = (observation.firstVisibleSlotIndex - 12).coerceAtLeast(0)
    val end = (observation.lastVisibleSlotIndex + 12).coerceAtMost(snapshot.slots.lastIndex)
    if (start > end) return emptyList()
    return snapshot.slots.subList(start, end + 1).asSequence().map { it.dayId }.distinct()
        .filterNot { it in snapshot.loadedDayIds || it in failedDays }.take(4).toList()
}
