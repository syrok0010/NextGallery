package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import com.syrok0010.nextgallery.ui.TimelineUiState
import com.syrok0010.nextgallery.ui.uiText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    suspend fun loadTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    ): List<MediaItem>

    suspend fun loadThumbnails(
        credentials: AccountCredentials,
        fileIds: List<Long>,
        etagsByFileId: Map<Long, String?>,
    ): List<ThumbnailKey>
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
    private val thumbnailBatchSize: Int = DEFAULT_THUMBNAIL_BATCH_SIZE,
) : TimelineViewportController {
    private var pendingObservationJob: Job? = null
    private val thumbnailLoadJobs = mutableMapOf<Job, Set<Long>>()
    private var activeObservation: TimelineViewportObservation? = null

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
        thumbnailLoadJobs.keys.toList().forEach(Job::cancel)
        thumbnailLoadJobs.clear()
        activeObservation = null
    }

    private fun acceptObservation(observation: TimelineViewportObservation) {
        activeObservation = observation
        processObservation(observation)
    }

    private fun cancelObsoleteThumbnailLoads(desiredFileIds: Set<Long>) {
        val obsoleteJobs = thumbnailLoadJobs
            .filterValues { loadingFileIds -> loadingFileIds.none(desiredFileIds::contains) }
            .keys
            .toList()
        obsoleteJobs.forEach { job ->
            thumbnailLoadJobs.remove(job)
            job.cancel()
        }
    }

    private fun processObservation(observation: TimelineViewportObservation) {
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

        val desiredFileIds = timeline.slots
            .asSequence()
            .drop(windowStart)
            .take(windowEnd - windowStart + 1)
            .mapNotNull { it.mediaItem?.fileId }
            .toSet()
        cancelObsoleteThumbnailLoads(desiredFileIds)

        loadVisibleThumbnails(
            credentials = session.credentials,
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
            runCatching { host.loadTimelineDays(session.credentials, dayIds) }
                .onSuccess { items ->
                    var loadedItemCount: Int? = null

                    host.updateTimeline { state ->
                        val currentTimeline = state.snapshot
                        val updatedTimeline = currentTimeline?.let {
                            TimelineSnapshotAssembler.mergeLoadedItems(
                                snapshot = it,
                                items = items,
                                loadedDayIds = dayIds.toSet(),
                            )
                        }
                        loadedItemCount = updatedTimeline?.items?.size ?: currentTimeline?.items?.size

                        state.copy(
                            snapshot = updatedTimeline,
                            loadingDayIds = state.loadingDayIds - dayIds.toSet(),
                            failedDayIds = state.failedDayIds - dayIds.toSet(),
                            loadMoreError = null,
                        )
                    }

                    loadedItemCount?.let(host::showLoadedItemsStatus)

                    val updatedSession = host.currentSession() ?: return@onSuccess
                    loadVisibleThumbnails(
                        credentials = updatedSession.credentials,
                        timelineState = updatedSession.timelineState,
                        windowStart = windowStart,
                        windowEnd = windowEnd,
                    )
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

    private fun loadVisibleThumbnails(
        credentials: AccountCredentials,
        timelineState: TimelineUiState,
        windowStart: Int,
        windowEnd: Int,
    ) {
        if (thumbnailLoadJobs.size >= MAX_CONCURRENT_THUMBNAIL_BATCHES) {
            return
        }

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
            .filterNot { it in timelineState.thumbnailKeys }
            .filterNot { it in timelineState.thumbnailLoadingFileIds }
            .filterNot { it in timelineState.thumbnailFailedFileIds }
            .take(thumbnailBatchSize)
            .toList()

        if (fileIds.isEmpty()) {
            return
        }

        val etagsByFileId = timeline.slots
            .asSequence()
            .drop(windowStart)
            .take(windowEnd - windowStart + 1)
            .mapNotNull { it.mediaItem }
            .associate { it.fileId to it.etag }

        host.updateTimeline { state ->
            state.copy(
                thumbnailLoadingFileIds = state.thumbnailLoadingFileIds + fileIds,
            )
        }

        lateinit var thumbnailLoadJob: Job
        thumbnailLoadJob = scope.launch {
            try {
                runCatching { host.loadThumbnails(credentials, fileIds, etagsByFileId) }
                    .onSuccess { thumbnailKeys ->
                        val keysByFileId = thumbnailKeys.associateBy { it.fileId }
                        val missingFileIds = fileIds.filterNot { it in keysByFileId }

                        host.updateTimeline { state ->
                            state.copy(
                                thumbnailKeys = state.thumbnailKeys + keysByFileId,
                                thumbnailLoadingFileIds = state.thumbnailLoadingFileIds - fileIds.toSet(),
                                thumbnailFailedFileIds = state.thumbnailFailedFileIds + missingFileIds,
                            )
                        }
                    }
                    .onFailure { failure ->
                        if (failure is CancellationException) {
                            throw failure
                        }

                        host.updateTimeline { state ->
                            state.copy(
                                thumbnailLoadingFileIds = state.thumbnailLoadingFileIds - fileIds.toSet(),
                                thumbnailFailedFileIds = state.thumbnailFailedFileIds + fileIds,
                            )
                        }
                    }
            } finally {
                thumbnailLoadJobs.remove(thumbnailLoadJob)
                if (!isActive) {
                    host.updateTimeline { state ->
                        state.copy(
                            thumbnailLoadingFileIds = state.thumbnailLoadingFileIds - fileIds.toSet(),
                        )
                    }
                } else {
                    activeObservation?.let(::processObservation)
                }
            }
        }
        thumbnailLoadJobs[thumbnailLoadJob] = fileIds.toSet()
    }
}

private const val DEFAULT_SCROLLBAR_DRAG_LOAD_DEBOUNCE_MILLIS = 450L
private const val DEFAULT_INITIAL_PREFETCH_LAST_VISIBLE_INDEX = 11
private const val DEFAULT_PREFETCH_SLOTS = 12
private const val DEFAULT_DAY_BATCH_SIZE = 4
private const val DEFAULT_THUMBNAIL_BATCH_SIZE = 12
private const val MAX_CONCURRENT_THUMBNAIL_BATCHES = 2
