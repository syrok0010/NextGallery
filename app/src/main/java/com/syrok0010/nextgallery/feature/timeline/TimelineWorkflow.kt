package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class TimelineWorkflowState(
    val snapshot: TimelineSnapshot? = null,
    val loadingDayIds: Set<Int> = emptySet(),
    val failedDayIds: Set<Int> = emptySet(),
)

internal data class TimelineLoadRange(val first: Int, val last: Int, val debounced: Boolean = false)

internal interface TimelineCommands { fun requestRange(range: TimelineLoadRange) }

/** Loads the remote timeline structure and hydrates its visible ranges. */
internal class TimelineWorkflow(
    private val credentials: AccountCredentials,
    private val source: RemoteTimelineSource,
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 450,
) {
    private val mutableState = MutableStateFlow(TimelineWorkflowState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var hydrationJob: Job? = null
    private var rangeJob: Job? = null
    private var range: TimelineLoadRange? = null
    private var generation = 0L

    init { refresh() }

    fun refresh() {
        val currentGeneration = ++generation
        refreshJob?.cancel()
        hydrationJob?.cancel()
        mutableState.update { it.copy(loadingDayIds = emptySet(), failedDayIds = emptySet()) }
        refreshJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (state.value.snapshot == null) {
                    source.loadCachedTimeline(credentials)?.let { cached ->
                        if (currentGeneration != generation) return@launch
                        mutableState.update { it.copy(snapshot = cached) }
                    }
                }
                val remote = source.loadInitialTimeline(credentials)
                if (currentGeneration != generation) return@launch
                mutableState.update { it.copy(snapshot = remote) }
                if (range == null) range = TimelineLoadRange(0, 11)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cached snapshot, when present, remains usable; a later refresh can recover.
            } finally {
                if (currentGeneration == generation) {
                    refreshJob = null
                    hydrateRange()
                }
            }
        }
        refreshJob?.start()
    }

    fun requestRange(request: TimelineLoadRange) {
        rangeJob?.cancel()
        if (request.debounced) {
            range = null
            rangeJob = scope.launch {
                kotlinx.coroutines.delay(debounceMillis)
                range = request
                hydrateRange()
            }
        } else {
            range = request
            hydrateRange()
        }
    }

    private fun hydrateRange() {
        if (refreshJob?.isActive == true || hydrationJob?.isActive == true) return
        val currentGeneration = generation
        hydrationJob = scope.launch(start = CoroutineStart.LAZY) {
            while (currentGeneration == generation) {
                val observation = range ?: break
                val snapshot = state.value.snapshot ?: break
                val dayIds = daysForRange(snapshot, observation, state.value.failedDayIds)
                if (dayIds.isEmpty()) break
                mutableState.update { it.copy(loadingDayIds = dayIds.toSet()) }
                try {
                    val items = source.loadTimelineDays(credentials, dayIds)
                    if (currentGeneration != generation) return@launch
                    mutableState.update {
                        it.copy(
                            snapshot = TimelineSnapshotAssembler.mergeLoadedItems(
                                snapshot = checkNotNull(it.snapshot),
                                items = items,
                                loadedDayIds = dayIds.toSet(),
                            ),
                            loadingDayIds = emptySet(),
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    if (currentGeneration == generation) mutableState.update {
                        it.copy(loadingDayIds = emptySet(), failedDayIds = it.failedDayIds + dayIds)
                    }
                }
            }
        }
        hydrationJob?.start()
    }
}

internal fun daysForRange(
    snapshot: TimelineSnapshot,
    observation: TimelineLoadRange,
    failedDays: Set<Int>,
): List<Int> {
    val start = (observation.first - 12).coerceAtLeast(0)
    val end = (observation.last + 12).coerceAtMost(snapshot.slots.lastIndex)
    if (start > end) return emptyList()
    return snapshot.slots.subList(start, end + 1).asSequence()
        .map { it.dayId }.distinct()
        .filterNot { it in snapshot.loadedDayIds || it in failedDays }
        .take(4).toList()
}
