package com.syrok0010.nextgallery.feature.timeline.local

import com.syrok0010.nextgallery.core.media.LocalMediaProjection
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaIdentityResolution
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.feature.timeline.UnifiedTimelineProjection
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaSourceTest {
    @Test
    fun `incremental indexing reconciles each local identity only once`() = runBlocking {
        val batchCount = 20
        val batchSize = 200
        val registry = CountingMediaIdentityRegistry()
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flowOf(
                    *Array(batchCount) { batchIndex ->
                        val indexedCount = (batchIndex + 1) * batchSize
                        LocalMediaBatch(
                            metadata = List(batchSize) { itemIndex ->
                                val index = batchIndex * batchSize + itemIndex
                                metadata("content://images/$index", taken = index * 1_000L + 1_000L)
                            },
                            progress = LocalMediaIndexProgress(
                                indexedCount = indexedCount,
                                totalCount = batchCount * batchSize,
                            ),
                        )
                    },
                )
            },
            projectionStore = InMemoryLocalMediaProjectionStore(),
            identityRegistry = registry,
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
            batchSize = batchSize,
        )
        val projection = UnifiedTimelineProjection()

        source.updates(emptyFlow()).take(batchCount + 1).collect { state ->
            projection.replaceLocalItems(LocalMediaProjection(state.items))
        }

        assertEquals(batchCount * batchSize, registry.resolvedCandidateCount)
    }

    @Test
    fun `cached projection is published before fresh MediaStore batches`() = runBlocking {
        val cached = localItem("content://images/cached", 100)
        val store = InMemoryLocalMediaProjectionStore(listOf(cached))
        val source = LocalMediaSource(
            reader = LocalMediaReader { batchSize ->
                assertEquals(2, batchSize)
                flowOf(
                    LocalMediaBatch(
                        metadata = listOf(
                            metadata("content://images/3", taken = 300_000),
                            metadata("content://video/2", modified = 250, video = true),
                        ),
                        progress = LocalMediaIndexProgress(indexedCount = 2, totalCount = 3),
                    ),
                    LocalMediaBatch(
                        metadata = listOf(metadata("content://images/1", added = 175)),
                        progress = LocalMediaIndexProgress(indexedCount = 3, totalCount = 3),
                    ),
                )
            },
            projectionStore = store,
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
            batchSize = 2,
            publicationInterval = Duration.INFINITE,
        )

        val states = source.updates(emptyFlow()).take(3).toList()

        assertEquals(listOf(cached), states[0].items)
        assertNull(states[0].progress)
        assertEquals(
            listOf("content://images/cached"),
            states[1].items.map(MediaItem::localContentUri),
        )
        assertEquals(LocalMediaIndexProgress(indexedCount = 2, totalCount = 3), states[1].progress)
        assertEquals(
            listOf("content://images/3", "content://video/2", "content://images/1"),
            states[2].items.map(MediaItem::localContentUri),
        )
        assertNull(states[2].progress)
        assertEquals(states[2].items, store.items)
    }

    @Test
    fun `mapping uses taken modified added date priority and drops unusable rows`() = runBlocking {
        val store = InMemoryLocalMediaProjectionStore()
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flowOf(
                    LocalMediaBatch(
                        metadata = listOf(
                            metadata("content://images/1", taken = 300_000, modified = 200, added = 100),
                            metadata("content://video/2", modified = 250, added = 150, video = true),
                            metadata("content://images/3", added = 175),
                            metadata("content://images/no-date"),
                        ),
                        progress = LocalMediaIndexProgress(indexedCount = 4, totalCount = 4),
                    ),
                )
            },
            projectionStore = store,
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        )

        val finalState = source.updates(emptyFlow()).take(2).toList().last()

        assertEquals(listOf(300L, 250L, 175L), finalState.items.map { it.takenAtEpochSeconds })
        assertEquals("fc04c0511168c77b574e1114c979c5b8", finalState.items[0].auid)
        assertEquals("93f49276c1fbb6e6f65519f19343f9ea", finalState.items[0].buid)
        assertEquals(listOf(false, true, false), finalState.items.map { it.isVideo })
        assertEquals(2L, finalState.items[1].videoDurationSeconds)
        assertEquals(
            MediaAssetRef.LocalContent(
                contentUri = "content://video/2",
                modifiedAtEpochSeconds = 250,
            ),
            finalState.items[1].assetRef,
        )
    }

    @Test
    fun `Memories timeline date does not replace raw DATE_TAKEN in AUID`() = runBlocking {
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flowOf(
                    LocalMediaBatch(
                        metadata = listOf(
                            metadata(
                                uri = "content://images/1",
                                taken = 1_000,
                                memoriesTimelineEpochSeconds = 200,
                            ),
                        ),
                        progress = LocalMediaIndexProgress(indexedCount = 1, totalCount = 1),
                    ),
                )
            },
            projectionStore = InMemoryLocalMediaProjectionStore(),
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        )

        val item = source.updates(emptyFlow()).take(2).toList().last().items.single()

        assertEquals(200L, item.takenAtEpochSeconds)
        assertEquals("33d6548e48d4318ceb0e3916a79afc84", item.auid)
    }

    @Test
    fun `burst of MediaStore changes publishes one reconciled projection after debounce`() = runBlocking {
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val store = InMemoryLocalMediaProjectionStore()
        var scanNumber = 0
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                scanNumber += 1
                flowOf(
                    LocalMediaBatch(
                        metadata = listOf(metadata("content://images/scan-$scanNumber", taken = scanNumber * 1_000L)),
                        progress = LocalMediaIndexProgress(indexedCount = 1, totalCount = 1),
                    ),
                )
            },
            projectionStore = store,
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver(changes::receiveAsFlow),
            changeDebounce = 25.milliseconds,
        )
        val firstScan = CompletableDeferred<Unit>()
        val secondScan = CompletableDeferred<Unit>()
        val publishedNames = mutableListOf<String>()
        val collection = launch {
            source.updates(emptyFlow()).collect { state ->
                state.items.singleOrNull()?.displayName?.let { name ->
                    publishedNames += name
                    when (name) {
                        "scan-1" -> firstScan.complete(Unit)
                        "scan-2" -> secondScan.complete(Unit)
                    }
                }
            }
        }
        withTimeout(1_000) { firstScan.await() }

        repeat(3) { changes.send(Unit) }
        withTimeout(1_000) { secondScan.await() }
        collection.cancelAndJoin()

        assertEquals(2, scanNumber)
        assertEquals(listOf("scan-1", "scan-2"), publishedNames.distinct())
    }

    @Test
    fun `unchanged 20000 item rescan keeps one published list across 100 batches`() = runBlocking {
        var rows = List(20_000) { metadata("content://images/$it", taken = (it + 1) * 1_000L) }
        val store = InMemoryLocalMediaProjectionStore()
        val registry = CountingMediaIdentityRegistry()
        fun source() = LocalMediaSource(
            reader = LocalMediaReader {
                flow {
                    rows.chunked(200).forEachIndexed { index, batch ->
                        emit(LocalMediaBatch(batch, LocalMediaIndexProgress((index + 1) * 200, rows.size)))
                    }
                }
            },
            projectionStore = store,
            identityRegistry = registry,
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
            publicationInterval = Duration.INFINITE,
        )
        val cold = source().updates(emptyFlow()).take(101).toList()
        assertTrue(cold.dropLast(1).all { it.items.isEmpty() })
        assertEquals(20_000, cold.last().items.size)
        val warm = source().updates(emptyFlow()).take(101).toList()
        warm.forEach { assertSame(warm.first().items, it.items) }
        assertNull(warm.last().progress)
        assertEquals(20_000, registry.resolvedCandidateCount)
        rows = rows.dropLast(1) + rows.last().copy(displayName = "renamed.jpg")
        val changed = source().updates(emptyFlow()).take(101).toList()
        assertEquals(20_001, registry.resolvedCandidateCount)
        assertTrue(changed.last().items.any { it.displayName == "renamed.jpg" })
    }

    @Test
    fun `incomplete scan preserves cached rows and does not reconcile deletions`() = runBlocking {
        val cached = localItem("content://images/cached", 100)
        val store = InMemoryLocalMediaProjectionStore(listOf(cached))
        val source = LocalMediaSource(
            reader = LocalMediaReader { emptyFlow() },
            projectionStore = store,
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        )
        val updates = source.updates(emptyFlow()).take(2).toList()
        assertTrue(updates.last().failure)
        assertEquals(listOf(cached), store.items)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `failed scan recovers on next observer trigger in the same subscription`() = kotlinx.coroutines.test.runTest {
        val changes = Channel<Unit>(Channel.UNLIMITED)
        var scans = 0
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flow {
                    scans++
                    if (scans == 1) error("temporary read failure")
                    emit(LocalMediaBatch(listOf(metadata("content://images/recovered", taken = 1_000)),
                        LocalMediaIndexProgress(1, 1)))
                }
            },
            projectionStore = InMemoryLocalMediaProjectionStore(),
            identityRegistry = InMemoryMediaIdentityRegistry(),
            changeObserver = LocalMediaChangeObserver(changes::receiveAsFlow),
            computationDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            changeDebounce = 25.milliseconds,
        )
        val states = mutableListOf<LocalMediaIndexState>()
        backgroundScope.launch { source.updates(emptyFlow()).collect { states += it } }
        testScheduler.runCurrent()
        assertTrue(states.last().failure)
        changes.send(Unit)
        testScheduler.advanceTimeBy(26)
        testScheduler.runCurrent()
        assertEquals(2, scans)
        assertTrue(!states.last().failure)
        assertEquals("recovered", states.last().items.single().displayName)
    }

    private class InMemoryLocalMediaProjectionStore(
        initialItems: List<MediaItem> = emptyList(),
    ) : LocalMediaProjectionStore {
        var items = initialItems

        override suspend fun loadLocalMediaProjection(): List<MediaItem> = items

        override suspend fun saveLocalMediaBatch(items: List<MediaItem>) {
            val updates = items.associateBy(MediaItem::localContentUri)
            this.items = (this.items.filterNot { it.localContentUri() in updates } + items)
                .sortedByDescending { it.takenAtEpochSeconds }
        }

        override suspend fun finishLocalMediaReconciliation(contentUris: Set<String>) {
            items = items.filter { it.localContentUri() in contentUris }
        }
    }

    private class CountingMediaIdentityRegistry : MediaIdentityRegistry {
        private val delegate = InMemoryMediaIdentityRegistry()
        var resolvedCandidateCount = 0

        override suspend fun resolve(candidates: List<MediaIdentityCandidate>): MediaIdentityResolution {
            resolvedCandidateCount += candidates.size
            return delegate.resolve(candidates)
        }

        override suspend fun removeSource(source: MediaSourceKind) {
            delegate.removeSource(source)
        }
    }

    private fun metadata(
        uri: String,
        taken: Long? = null,
        modified: Long? = null,
        added: Long? = null,
        video: Boolean = false,
        memoriesTimelineEpochSeconds: Long? = null,
    ) = LocalMediaMetadata(
        contentUri = uri,
        displayName = uri.substringAfterLast('/'),
        mimeType = if (video) "video/mp4" else "image/jpeg",
        width = 100,
        height = 200,
        sizeBytes = 1_000,
        dateTakenMillis = taken,
        memoriesTimelineEpochSeconds = memoriesTimelineEpochSeconds,
        dateModifiedSeconds = modified,
        dateAddedSeconds = added,
        durationMillis = if (video) 2_500 else null,
        isVideo = video,
    )

    private fun localItem(uri: String, timestamp: Long) = MediaItem(
        mediaId = MediaId("stable:$uri"),
        dayId = Math.floorDiv(timestamp, 86_400L).toInt(),
        displayName = uri.substringAfterLast('/'),
        mimeType = "image/jpeg",
        width = 100,
        height = 200,
        etag = null,
        livePhotoId = null,
        auid = null,
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = timestamp,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.LocalContent(uri, timestamp),
    )
}

private fun MediaItem.localContentUri(): String =
    (assetRef as MediaAssetRef.LocalContent).contentUri
