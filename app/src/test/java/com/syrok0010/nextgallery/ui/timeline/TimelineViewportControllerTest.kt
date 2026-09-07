package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.ui.TimelineUiState
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineViewportControllerTest {
    @Test
    fun `viewer range hydrates both sides beyond the first batch without another gesture`() = runBlocking {
        val days = (10..30).map { TimelineDay(dayId = it, count = 1) }
        val host = FakeTimelineViewportHost(
            TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(
                    snapshot = timelineSnapshot(days = days, loadedDayIds = setOf(20)),
                ),
            ),
        ).apply {
            dayLoader = { _, ids -> ids.map { mediaItem(it.toLong() * 100, it) } }
        }
        val controller = DefaultTimelineViewportController(scope = this, host = host, prefetchSlots = 0)

        controller.onViewportObservation(TimelineViewportObservation(0, 20, TimelineViewportLoadingMode.Immediate))

        awaitUntil { host.requireSession().timelineState.snapshot?.loadedDayIds == (10..30).toSet() }
        assertEquals((10..30).filter { it != 20 }, host.dayLoadRequests.flatten())
    }

    @Test
    fun `hydration continues with the latest viewport instead of the old window`() = runBlocking {
        val firstBatch = CompletableDeferred<Unit>()
        val host = FakeTimelineViewportHost(
            TimelineViewportSession(
                credentials(),
                TimelineUiState(snapshot = timelineSnapshot(days = (10..30).map { TimelineDay(it, 1) })),
            ),
        ).apply {
            dayLoader = { _, ids ->
                if (ids.first() == 10) firstBatch.await()
                ids.map { mediaItem(it.toLong() * 100, it) }
            }
        }
        val controller = DefaultTimelineViewportController(scope = this, host = host, prefetchSlots = 0)
        controller.onViewportObservation(TimelineViewportObservation(0, 10, TimelineViewportLoadingMode.Immediate))
        awaitUntil { host.dayLoadRequests.isNotEmpty() }
        controller.onViewportObservation(TimelineViewportObservation(20, 20, TimelineViewportLoadingMode.Immediate))
        firstBatch.complete(Unit)
        awaitUntil { host.requireSession().timelineState.loadingDayIds.isEmpty() }
        assertEquals(setOf(10, 11, 12, 13, 30), host.dayLoadRequests.flatten().toSet())
    }

    @Test
    fun `cancel prevents further batches after an in flight request completes`() = runBlocking {
        val firstBatch = CompletableDeferred<Unit>()
        val host = FakeTimelineViewportHost(
            TimelineViewportSession(
                credentials(),
                TimelineUiState(snapshot = timelineSnapshot(days = (10..30).map { TimelineDay(it, 1) })),
            ),
        ).apply {
            dayLoader = { _, ids ->
                firstBatch.await()
                ids.map { mediaItem(it.toLong() * 100, it) }
            }
        }
        val controller = DefaultTimelineViewportController(scope = this, host = host, prefetchSlots = 0)
        controller.onViewportObservation(TimelineViewportObservation(0, 20, TimelineViewportLoadingMode.Immediate))
        awaitUntil { host.dayLoadRequests.isNotEmpty() }
        controller.cancel()
        firstBatch.complete(Unit)
        awaitUntil { host.requireSession().timelineState.loadingDayIds.isEmpty() }
        assertEquals(listOf(listOf(10, 11, 12, 13)), host.dayLoadRequests)
    }

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
        awaitUntil { host.dayLoadRequests == listOf(listOf(11, 14), listOf(15)) }

        assertEquals(listOf(listOf(11, 14), listOf(15)), host.dayLoadRequests)
    }

    @Test
    fun `successful day hydration preserves published snapshot and reports its loaded count`() = runBlocking {
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
        }
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            prefetchSlots = 0,
            dayBatchSize = 1,
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
                host.loadedItemsStatusCounts == listOf(1) &&
                host.requireSession().timelineState.snapshot?.loadedDayIds == setOf(dayId)
        }

        assertEquals(listOf(listOf(dayId)), host.dayLoadRequests)
        assertEquals(listOf(1), host.loadedItemsStatusCounts)
        assertEquals(setOf(dayId), host.requireSession().timelineState.snapshot?.loadedDayIds)
        assertSame(host.publishedSnapshot, host.requireSession().timelineState.snapshot)
        assertEquals(emptySet<Int>(), host.requireSession().timelineState.loadingDayIds)
        assertEquals(emptySet<Int>(), host.requireSession().timelineState.failedDayIds)
    }

    @Test
    fun `failed hydration preserves snapshot and marks requested days as failed`() = runBlocking {
        val snapshot = timelineSnapshot(days = listOf(TimelineDay(dayId = 10, count = 1)))
        val host = FakeTimelineViewportHost(
            initialSession = TimelineViewportSession(
                credentials = credentials(),
                timelineState = TimelineUiState(snapshot = snapshot),
            ),
        ).apply {
            dayLoader = { _, _ -> error("Network unavailable") }
        }
        val controller = DefaultTimelineViewportController(
            scope = this,
            host = host,
            prefetchSlots = 0,
        )

        controller.prefetchFromStart()
        awaitUntil { host.requireSession().timelineState.failedDayIds == setOf(10) }

        val state = host.requireSession().timelineState
        assertSame(snapshot, state.snapshot)
        assertEquals(emptySet<Int>(), state.loadingDayIds)
        assertNotNull(state.loadMoreError)
        assertEquals(emptyList<Int>(), host.loadedItemsStatusCounts)
        controller.prefetchFromStart()
        assertEquals(listOf(listOf(10)), host.dayLoadRequests)
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
        var publishedSnapshot: TimelineSnapshot? = null
        val dayLoadRequests = mutableListOf<List<Int>>()
        val loadedItemsStatusCounts = mutableListOf<Int>()

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

        override suspend fun loadAndPublishTimelineDays(
            credentials: AccountCredentials,
            dayIds: List<Int>,
        ) {
            dayLoadRequests += dayIds
            val items = dayLoader(credentials, dayIds)
            updateTimeline { state ->
                publishedSnapshot = state.snapshot?.let {
                    TimelineSnapshotAssembler.mergeLoadedItems(it, items, dayIds.toSet())
                }
                state.copy(snapshot = publishedSnapshot)
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
            mediaId = MediaId("remote-$fileId"),
            remoteFileId = fileId,
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

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }
}
