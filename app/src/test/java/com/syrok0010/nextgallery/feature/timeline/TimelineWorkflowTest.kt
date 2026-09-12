package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TimelineWorkflowTest {
    @Test fun `failed day is retried by refresh and actual projection is published`() = runTest {
        val source = Source()
        source.fail = true
        val workflow = workflow(source)
        runCurrent()
        assertEquals(setOf(10), workflow.state.value.failedDayIds)
        source.fail = false
        workflow.refresh()
        runCurrent()
        assertEquals(2, source.requests.size)
        assertEquals(setOf(10), workflow.state.value.snapshot!!.loadedDayIds)
        assertEquals(listOf(1L), workflow.state.value.snapshot!!.items.map { it.remoteFileId })
        assertTrue(workflow.state.value.failedDayIds.isEmpty())
    }

    @Test fun `refresh cancels obsolete hydration before publishing new generation`() = runTest {
        val source = Source()
        val blocked = CompletableDeferred<Unit>()
        source.blocked = blocked
        val workflow = workflow(source)
        runCurrent()
        assertEquals(1, source.requests.size)
        source.blocked = null
        workflow.refresh()
        runCurrent()
        blocked.complete(Unit)
        runCurrent()
        assertEquals(setOf(10), workflow.state.value.snapshot!!.loadedDayIds)
        assertEquals(1, workflow.state.value.snapshot!!.items.size)
    }

    @Test fun `debounced viewport uses latest observation and continues across batches`() = runTest {
        val source = Source(days = (10..100).map { TimelineDay(it, 1) })
        val workflow = workflow(source)
        runCurrent()
        workflow.observeViewport(TimelineViewportObservation(80, 80, TimelineViewportLoadingMode.Debounced))
        advanceTimeBy(200)
        val before = source.requests.size
        workflow.observeViewport(TimelineViewportObservation(78, 80, TimelineViewportLoadingMode.Debounced))
        advanceTimeBy(449)
        runCurrent()
        assertEquals(before, source.requests.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals((10..33).toSet() + (76..100).toSet(), workflow.state.value.snapshot!!.loadedDayIds)
    }

    @Test fun `initial hydration expands viewport and excludes failed days on subsequent observations`() = runTest {
        val source = Source(days = (10..100).map { TimelineDay(it, 1) })
        source.fail = true
        val workflow = workflow(source)
        runCurrent()
        assertEquals((10..33).toSet(), workflow.state.value.failedDayIds)
        assertTrue(source.requests.all { it.size <= 4 })
        val count = source.requests.size
        workflow.observeViewport(TimelineViewportObservation(0, 11, TimelineViewportLoadingMode.Immediate))
        runCurrent()
        assertEquals(count, source.requests.size)
    }

    @Test fun `local permission revoke and regrant cannot erase the latest local publication`() = runTest {
        val source = Source()
        source.initialFailure = true
        val local = mediaItem(99, 10).copy(
            assetRef = MediaAssetRef.LocalContent("content://images/99", 1),
            takenAtEpochSeconds = 10 * 86_400L,
        )
        val workflow = TimelineWorkflow(credentials(), source, {
            kotlinx.coroutines.flow.flowOf(
                com.syrok0010.nextgallery.feature.timeline.local.LocalMediaIndexState(listOf(local), null),
            )
        }, backgroundScope, UnifiedTimelineProjection(StandardTestDispatcher(testScheduler)))
        runCurrent()
        val full = com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode.Full
        val denied = com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode.Denied
        workflow.updateLocalAccess(full)
        runCurrent()
        assertEquals(TimelineOperation.Failed, workflow.state.value.remote)
        assertEquals(1, workflow.state.value.snapshot!!.items.size)
        workflow.updateLocalAccess(denied)
        workflow.updateLocalAccess(full)
        runCurrent()
        assertEquals(listOf(local), workflow.state.value.snapshot!!.items)
        assertEquals(TimelineOperation.Failed, workflow.state.value.remote)
        workflow.updateLocalAccess(denied)
        runCurrent()
        assertTrue(workflow.state.value.snapshot?.items.orEmpty().isEmpty())
    }

    @Test fun `immediate dispatcher does not leave refresh marked active before hydration`() = runTest {
        val source = Source()
        val immediate = UnconfinedTestDispatcher(testScheduler)
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + immediate)
        val workflow = TimelineWorkflow(credentials(), source, { emptyFlow() }, scope,
            UnifiedTimelineProjection(immediate))
        runCurrent()
        assertEquals(setOf(10), workflow.state.value.snapshot!!.loadedDayIds)
    }

    @Test fun `ending session scope cancels pending hydration without publishing errors`() = runTest {
        val source = Source()
        val gate = CompletableDeferred<Unit>()
        source.blocked = gate
        val job = kotlinx.coroutines.Job(backgroundScope.coroutineContext[kotlinx.coroutines.Job])
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + job)
        val workflow = TimelineWorkflow(credentials(), source, { emptyFlow() }, scope,
            UnifiedTimelineProjection(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertEquals(setOf(10), workflow.state.value.loadingDayIds)
        val before = workflow.state.value
        job.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(before, workflow.state.value)
    }

    private fun TestScope.workflow(source: Source) = TimelineWorkflow(
        credentials(), source, { emptyFlow() }, backgroundScope,
        UnifiedTimelineProjection(StandardTestDispatcher(testScheduler)),
    )

    private inner class Source(val days: List<TimelineDay> = listOf(TimelineDay(10, 1))) : RemoteTimelineSource {
        var initialFailure = false
        var fail = false
        var blocked: CompletableDeferred<Unit>? = null
        val requests = mutableListOf<List<Int>>()
        override suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot? = null
        override suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
            if (initialFailure) error("offline")
            return timelineSnapshot(days)
        }
        override suspend fun loadTimelineDays(credentials: AccountCredentials, dayIds: List<Int>): List<MediaItem> {
            requests += dayIds
            blocked?.await()
            if (fail) error("transient")
            return dayIds.map { mediaItem((it - 9).toLong(), it) }
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
            dayId = dayId,
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
