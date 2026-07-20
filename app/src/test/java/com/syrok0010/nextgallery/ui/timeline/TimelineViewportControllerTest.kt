package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.ui.TimelineUiState
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineViewportControllerTest {
    @Test
    fun `viewport batching skips loaded loading and failed days`() = runBlocking {
        val days = (10..15).map { TimelineDay(dayId = it, count = 1) }
        val host = FakeTimelineViewportHost(
            initialSession = TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(
                    snapshot = timelineSnapshot(
                        days = days,
                        loadedDayIds = setOf(10),
                    ),
                    loadingDayIds = setOf(12),
                    failedDayIds = setOf(13),
                ),
            ),
        )
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            prefetchSlots = 0,
            dayBatchSize = 2,
        )

        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 0,
                lastVisibleSlotIndex = 5,
                loadingMode = TimelineViewportLoadingMode.Immediate,
            ),
        )
        awaitUntil { host.dayLoadRequests == listOf(listOf(11, 14)) }

        assertEquals(listOf(listOf(11, 14)), host.dayLoadRequests)
    }

    @Test
    fun `successful day hydration triggers thumbnail loading for newly visible items`() = runBlocking {
        val dayId = 10
        val host = FakeTimelineViewportHost(
            initialSession = TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(
                    snapshot = timelineSnapshot(
                        days = listOf(TimelineDay(dayId = dayId, count = 1)),
                    ),
                ),
            ),
        ).apply {
            dayLoader = { _, requestedDayIds ->
                requestedDayIds.map { requestedDayId ->
                    mediaItem(fileId = requestedDayId.toLong() * 100, dayId = requestedDayId)
                }
            }
            thumbnailLoader = { _, fileIds, _ ->
                fileIds.map(::thumbnail)
            }
        }
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            prefetchSlots = 0,
            dayBatchSize = 1,
            thumbnailBatchSize = 4,
        )

        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 0,
                lastVisibleSlotIndex = 0,
                loadingMode = TimelineViewportLoadingMode.Immediate,
            ),
        )
        awaitUntil {
            host.dayLoadRequests == listOf(listOf(dayId)) &&
                host.thumbnailLoadRequests == listOf(listOf(1000L)) &&
                host.loadedItemsStatusCounts == listOf(1) &&
                host.requireSession().timelineState.snapshot?.loadedDayIds == setOf(dayId) &&
                host.requireSession().timelineState.thumbnailPreviews.containsKey(1000L)
        }

        assertEquals(listOf(listOf(dayId)), host.dayLoadRequests)
        assertEquals(listOf(listOf(1000L)), host.thumbnailLoadRequests)
        assertEquals(listOf(1), host.loadedItemsStatusCounts)
        assertEquals(setOf(dayId), host.requireSession().timelineState.snapshot?.loadedDayIds)
        assertTrue(host.requireSession().timelineState.thumbnailPreviews.containsKey(1000L))
    }

    @Test
    fun `thumbnail batches stay bounded and skip already handled file ids`() = runBlocking {
        val days = (10..15).map { TimelineDay(dayId = it, count = 1) }
        val itemsByDay = days.associate { day ->
            day.dayId to listOf(mediaItem(fileId = (day.dayId - 9).toLong(), dayId = day.dayId))
        }
        val host = FakeTimelineViewportHost(
            initialSession = TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(
                    snapshot = timelineSnapshot(
                        days = days,
                        itemsByDay = itemsByDay,
                        loadedDayIds = days.mapTo(mutableSetOf()) { it.dayId },
                    ),
                    thumbnailPreviews = mapOf(1L to thumbnail(1L)),
                    thumbnailLoadingFileIds = setOf(2L),
                    thumbnailFailedFileIds = setOf(3L),
                ),
            ),
        ).apply {
            thumbnailLoader = { _, fileIds, _ ->
                delay(20)
                fileIds.map(::thumbnail)
            }
        }
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            prefetchSlots = 0,
            thumbnailBatchSize = 2,
        )

        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 0,
                lastVisibleSlotIndex = 5,
                loadingMode = TimelineViewportLoadingMode.Immediate,
            ),
        )
        awaitUntil {
            host.thumbnailLoadRequests == listOf(listOf(4L, 5L), listOf(6L)) &&
                host.requireSession().timelineState.thumbnailPreviews.keys.containsAll(listOf(4L, 5L, 6L))
        }

        assertEquals(listOf(listOf(4L, 5L), listOf(6L)), host.thumbnailLoadRequests)
        assertTrue(host.maxConcurrentThumbnailLoads <= 2)
        assertTrue(host.dayLoadRequests.isEmpty())
    }

    @Test
    fun `debounced observations collapse drag updates to the latest viewport`() = runBlocking {
        val days = (10..12).map { TimelineDay(dayId = it, count = 1) }
        val host = FakeTimelineViewportHost(
            initialSession = TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(
                    snapshot = timelineSnapshot(days = days),
                ),
            ),
        )
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            scrollbarDragLoadDebounceMillis = 30,
            prefetchSlots = 0,
            dayBatchSize = 1,
        )

        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 0,
                lastVisibleSlotIndex = 0,
                loadingMode = TimelineViewportLoadingMode.Debounced,
            ),
        )
        delay(10)
        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 1,
                lastVisibleSlotIndex = 1,
                loadingMode = TimelineViewportLoadingMode.Debounced,
            ),
        )
        delay(10)
        controller.onViewportObservation(
            TimelineViewportObservation(
                firstVisibleSlotIndex = 2,
                lastVisibleSlotIndex = 2,
                loadingMode = TimelineViewportLoadingMode.Debounced,
            ),
        )
        awaitUntil(timeoutMillis = 200) { host.dayLoadRequests == listOf(listOf(12)) }

        assertEquals(listOf(listOf(12)), host.dayLoadRequests)
    }

    private suspend fun awaitUntil(
        timeoutMillis: Long = 500,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() >= deadline) {
                throw AssertionError("Condition was not met within ${timeoutMillis}ms")
            }
            delay(10)
        }
    }

    private class FakeTimelineViewportHost(
        initialSession: TimelineViewportSession?,
    ) : TimelineViewportHost {
        var session: TimelineViewportSession? = initialSession
        var dayLoader: suspend (AccountCredentials, List<Int>) -> List<MediaItem> = { _, _ -> emptyList() }
        var thumbnailLoader: suspend (AccountCredentials, List<Long>, Map<Long, String?>) -> List<ThumbnailPreview> =
            { _, _, _ -> emptyList() }
        val dayLoadRequests = mutableListOf<List<Int>>()
        val thumbnailLoadRequests = mutableListOf<List<Long>>()
        val loadedItemsStatusCounts = mutableListOf<Int>()
        var maxConcurrentThumbnailLoads = 0
            private set
        private var concurrentThumbnailLoads = 0

        override fun currentSession(): TimelineViewportSession? = session

        override fun updateTimeline(transform: (TimelineUiState) -> TimelineUiState) {
            val currentSession = session ?: return
            session = currentSession.copy(
                timelineState = transform(currentSession.timelineState),
            )
        }

        override fun showLoadedItemsStatus(itemCount: Int) {
            loadedItemsStatusCounts += itemCount
        }

        override suspend fun loadTimelineDays(
            credentials: AccountCredentials,
            dayIds: List<Int>,
        ): List<MediaItem> {
            dayLoadRequests += dayIds
            return dayLoader(credentials, dayIds)
        }

        override suspend fun loadThumbnails(
            credentials: AccountCredentials,
            fileIds: List<Long>,
            etagsByFileId: Map<Long, String?>,
        ): List<ThumbnailPreview> {
            thumbnailLoadRequests += fileIds
            concurrentThumbnailLoads += 1
            maxConcurrentThumbnailLoads = maxOf(maxConcurrentThumbnailLoads, concurrentThumbnailLoads)
            return try {
                thumbnailLoader(credentials, fileIds, etagsByFileId)
            } finally {
                concurrentThumbnailLoads -= 1
            }
        }

        fun requireSession(): TimelineViewportSession {
            return checkNotNull(session)
        }
    }

    private fun timelineSnapshot(
        days: List<TimelineDay>,
        itemsByDay: Map<Int, List<MediaItem>> = emptyMap(),
        loadedDayIds: Set<Int> = emptySet(),
    ): TimelineSnapshot {
        val mediaItems = itemsByDay.values.flatten()
        return TimelineSnapshotAssembler.assemble(
            config = MemoriesConfig(
                version = "7.5.2",
                timelinePath = "/Photos",
                albumsEnabled = false,
                recognizeEnabled = false,
                faceRecognitionEnabled = false,
                previewGeneratorEnabled = false,
                stackRawFiles = false,
                dedupIdentical = false,
            ),
            days = days,
            mediaItems = mediaItems,
            loadedDayIds = loadedDayIds,
        )
    }

    private fun mediaItem(fileId: Long, dayId: Int): MediaItem {
        return MediaItem(
            fileId = fileId,
            dayId = dayId,
            day = LocalDate.ofEpochDay(dayId.toLong()),
            displayName = "file-$fileId",
            mimeType = "image/jpeg",
            width = 512,
            height = 512,
            etag = "etag-$fileId",
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = null,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.MemoriesFile(photoFileId = fileId),
        )
    }

    private fun thumbnail(fileId: Long): ThumbnailPreview {
        return ThumbnailPreview(
            fileId = fileId,
            requestId = fileId.toInt(),
            mimeType = "image/jpeg",
            bytes = byteArrayOf(fileId.toByte()),
        )
    }

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }
}
