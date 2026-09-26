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
    private var requestedDays: List<Int>? = null
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
                if (requestedDays == null) requestedDays = remote.slots.take(24).map { it.dayId }.distinct()
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

    fun requestDays(dayIds: List<Int>, debounced: Boolean = false) {
        rangeJob?.cancel()
        if (debounced) {
            requestedDays = null
            rangeJob = scope.launch {
                kotlinx.coroutines.delay(debounceMillis)
                requestedDays = dayIds.distinct()
                hydrateRange()
            }
        } else {
            requestedDays = dayIds.distinct()
            hydrateRange()
        }
    }

    private fun hydrateRange() {
        if (refreshJob?.isActive == true || hydrationJob?.isActive == true) return
        val currentGeneration = generation
        hydrationJob = scope.launch(start = CoroutineStart.LAZY) {
            while (currentGeneration == generation) {
                val requested = requestedDays ?: break
                val snapshot = state.value.snapshot ?: break
                val remoteDays = snapshot.days.mapTo(mutableSetOf()) { it.dayId }
                val dayIds = requested.filter { it in remoteDays }
                    .filterNot { it in snapshot.loadedDayIds || it in state.value.failedDayIds }
                    .take(4)
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
