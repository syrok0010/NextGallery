package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.ui.TimelineUiState
import com.syrok0010.nextgallery.ui.uiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

internal data class TimelineViewportObservation(
    val firstVisibleSlotIndex: Int,
    val lastVisibleSlotIndex: Int,
    val loadingMode: TimelineViewportLoadingMode,
)

internal enum class TimelineViewportLoadingMode {
    Immediate,
    Debounced,
}

internal data class TimelineViewportSession(
    val credentials: AccountCredentials,
    val timelineState: TimelineUiState,
)

internal interface TimelineViewportHost {
    fun currentSession(): TimelineViewportSession?
    fun updateTimeline(transform: (TimelineUiState) -> TimelineUiState)
    fun showLoadedItemsStatus(itemCount: Int)
    suspend fun loadAndPublishTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    )
}

internal interface TimelineViewportController {
    fun prefetchFromStart()
    fun onViewportObservation(observation: TimelineViewportObservation)
    fun cancel()
}

internal class DefaultTimelineViewportController(
    private val scope: CoroutineScope,
    private val host: TimelineViewportHost,
    private val scrollbarDragLoadDebounceMillis: Long = DEFAULT_SCROLLBAR_DRAG_LOAD_DEBOUNCE_MILLIS,
    private val initialPrefetchLastVisibleIndex: Int = DEFAULT_INITIAL_PREFETCH_LAST_VISIBLE_INDEX,
    private val prefetchSlots: Int = DEFAULT_PREFETCH_SLOTS,
    private val dayBatchSize: Int = DEFAULT_DAY_BATCH_SIZE,
) : TimelineViewportController {
    private var pendingObservationJob: Job? = null

    override fun prefetchFromStart() {
        acceptObservation(
            observation = TimelineViewportObservation(
                firstVisibleSlotIndex = 0,
                lastVisibleSlotIndex = initialPrefetchLastVisibleIndex,
                loadingMode = TimelineViewportLoadingMode.Immediate,
            ),
        )
    }

    override fun onViewportObservation(observation: TimelineViewportObservation) {
        when (observation.loadingMode) {
            TimelineViewportLoadingMode.Immediate -> {
                pendingObservationJob?.cancel()
                pendingObservationJob = null
                acceptObservation(observation)
            }

            TimelineViewportLoadingMode.Debounced -> {
                pendingObservationJob?.cancel()
                pendingObservationJob = scope.launch {
                    delay(scrollbarDragLoadDebounceMillis.milliseconds)
                    acceptObservation(observation)
                    pendingObservationJob = null
                }
            }
        }
    }

    override fun cancel() {
        pendingObservationJob?.cancel()
        pendingObservationJob = null
    }

    private fun acceptObservation(observation: TimelineViewportObservation) {
        val session = host.currentSession() ?: return
        val timelineState = session.timelineState
        val timeline = timelineState.snapshot ?: return
        if (timeline.slots.isEmpty()) {
            return
        }

        val windowStart = (observation.firstVisibleSlotIndex - prefetchSlots).coerceAtLeast(0)
        val windowEnd = (observation.lastVisibleSlotIndex + prefetchSlots).coerceAtMost(timeline.slots.lastIndex)
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
            .filterNot { it in timelineState.loadingDayIds }
            .filterNot { it in timelineState.failedDayIds }
            .take(dayBatchSize)
            .toList()

        if (dayIds.isEmpty()) {
            return
        }

        host.updateTimeline { state ->
            state.copy(
                loadingDayIds = state.loadingDayIds + dayIds,
                loadMoreError = null,
            )
        }

        scope.launch {
            runCatching { host.loadAndPublishTimelineDays(session.credentials, dayIds) }
                .onSuccess {
                    host.updateTimeline { state ->
                        state.copy(
                            loadingDayIds = state.loadingDayIds - dayIds.toSet(),
                            failedDayIds = state.failedDayIds - dayIds.toSet(),
                            loadMoreError = null,
                        )
                    }

                    host.currentSession()?.timelineState?.snapshot?.items?.size
                        ?.let(host::showLoadedItemsStatus)
                }
                .onFailure {
                    host.updateTimeline { state ->
                        state.copy(
                            loadingDayIds = state.loadingDayIds - dayIds.toSet(),
                            failedDayIds = state.failedDayIds + dayIds,
                            loadMoreError = uiText(R.string.error_load_timeline_batch_failed),
                        )
                    }
                }
        }
    }

}

private const val DEFAULT_SCROLLBAR_DRAG_LOAD_DEBOUNCE_MILLIS = 450L
private const val DEFAULT_INITIAL_PREFETCH_LAST_VISIBLE_INDEX = 11
private const val DEFAULT_PREFETCH_SLOTS = 12
private const val DEFAULT_DAY_BATCH_SIZE = 4
