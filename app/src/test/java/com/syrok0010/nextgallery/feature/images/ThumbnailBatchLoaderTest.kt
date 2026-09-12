package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ThumbnailBatchLoaderTest {
    private val loaderScopes = mutableListOf<CoroutineScope>()

    @After
    fun stopLoaders() {
        loaderScopes.forEach { it.cancel() }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `concurrent requests are deduplicated and loaded in one batch`() = runTest {
        val credentials = credentials()
        val requests = listOf(
            thumbnailRequest(credentials, fileId = 1, etag = "etag-1"),
            thumbnailRequest(credentials, fileId = 2, etag = "etag-2"),
            thumbnailRequest(credentials, fileId = 1, etag = "etag-1"),
        )
        val loadedBatches = mutableListOf<List<ThumbnailKey>>()
        val loader = loader { _, keys ->
            loadedBatches += keys
            keys.toSet()
        }

        val results = requests.map { request ->
            async { loader.ensureAvailable(request) }
        }.awaitAll()

        assertEquals(listOf(listOf(1L, 2L)), loadedBatches.map { batch -> batch.map(ThumbnailKey::fileId) })
        assertTrue(results.all { it })
    }

    @Test
    fun `large request group is split by multipreview batch size`() = runTest {
        val credentials = credentials()
        val loadedBatchSizes = mutableListOf<Int>()
        val loader = loader(batchSize = 3) { _, keys ->
            loadedBatchSizes += keys.size
            keys.toSet()
        }

        (1L..7L).map { fileId ->
            async {
                loader.ensureAvailable(thumbnailRequest(credentials, fileId, etag = "etag-$fileId"))
            }
        }.awaitAll()

        assertEquals(listOf(3, 3, 1), loadedBatchSizes)
    }

    @Test
    fun `full batch flushes without waiting for time window`() = runTest {
        val credentials = credentials()
        val loader = loader(
            batchSize = 3,
            batchWindowMillis = 5_000,
        ) { _, keys ->
            keys.toSet()
        }

        val results = withTimeout(500.milliseconds) {
            (1L..3L).map { fileId ->
                async {
                    loader.ensureAvailable(thumbnailRequest(credentials, fileId, etag = "etag-$fileId"))
                }
            }.awaitAll()
        }

        assertTrue(results.all { it })
    }

    @Test
    fun `duplicate request joins batch that is already loading`() = runTest {
        val credentials = credentials()
        val request = thumbnailRequest(credentials, fileId = 42, etag = "etag-42")
        val batchStarted = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()
        var batchCalls = 0
        val loader = loader(batchWindowMillis = 0) { _, keys ->
            batchCalls += 1
            batchStarted.complete(Unit)
            releaseBatch.await()
            keys.toSet()
        }

        val firstResult = async { loader.ensureAvailable(request) }
        batchStarted.await()
        val duplicateResult = async(start = CoroutineStart.UNDISPATCHED) {
            loader.ensureAvailable(request)
        }
        releaseBatch.complete(Unit)

        assertTrue(firstResult.await())
        assertTrue(duplicateResult.await())
        assertEquals(1, batchCalls)
    }

    @Test
    fun `no more than configured number of batches load concurrently`() = runTest {
        val credentials = credentials()
        val activeBatches = AtomicInteger()
        val maximumActiveBatches = AtomicInteger()
        val fourBatchesStarted = CompletableDeferred<Unit>()
        val releaseBatches = CompletableDeferred<Unit>()

        val loader = loader(
            batchSize = 1,
            batchWindowMillis = 0,
            maxConcurrentBatches = 4,
        ) { _, keys ->
            val active = activeBatches.incrementAndGet()
            maximumActiveBatches.updateAndGet { current -> maxOf(current, active) }
            if (active == 4) {
                fourBatchesStarted.complete(Unit)
            }
            try {
                releaseBatches.await()
                keys.toSet()
            } finally {
                activeBatches.decrementAndGet()
            }
        }

        val results = (1L..8L).map { fileId ->
            async {
                loader.ensureAvailable(thumbnailRequest(credentials, fileId, etag = "etag-$fileId"))
            }
        }
        withTimeout(500.milliseconds) {
            fourBatchesStarted.await()
        }
        assertEquals(4, maximumActiveBatches.get())

        releaseBatches.complete(Unit)
        assertTrue(results.awaitAll().all { it })
        assertEquals(4, maximumActiveBatches.get())
    }

    @Test
    fun `cancelled request in pending batch is not loaded and empty batch is not started`() = runTest {
        val credentials = credentials()
        val request = thumbnailRequest(credentials, fileId = 10, etag = "etag-10")
        var batchCalls = 0
        val loader = loader(
            batchSize = 5,
            batchWindowMillis = 50,
        ) { _, _ ->
            batchCalls += 1
            emptySet()
        }

        val deferred = async { loader.ensureAvailable(request) }
        delay(20.milliseconds)
        deferred.cancelAndJoin()

        // Wait past the flush deadline: checking before it cannot detect a load.
        delay(100.milliseconds)
        assertEquals(0, batchCalls)
    }

    @Test
    fun `cancelling one of multiple requests in pending batch removes only cancelled request`() = runTest {
        val credentials = credentials()
        val request1 = thumbnailRequest(credentials, fileId = 11, etag = "etag-11")
        val request2 = thumbnailRequest(credentials, fileId = 12, etag = "etag-12")
        val loadedKeys = mutableListOf<ThumbnailKey>()
        val loader = loader(
            batchSize = 5,
            batchWindowMillis = 100,
        ) { _, keys ->
            loadedKeys += keys
            keys.toSet()
        }

        val deferred1 = async { loader.ensureAvailable(request1) }
        val deferred2 = async { loader.ensureAvailable(request2) }
        delay(20.milliseconds)
        deferred1.cancel()

        assertTrue(deferred2.await())
        assertEquals(listOf(12L), loadedKeys.map(ThumbnailKey::fileId))
    }

    @Test
    fun `duplicate request completes even if first requester cancels`() = runTest {
        val credentials = credentials()
        val request = thumbnailRequest(credentials, fileId = 30, etag = "etag-30")
        val loader = loader(
            batchSize = 5,
            batchWindowMillis = 100,
        ) { _, keys ->
            keys.toSet()
        }

        val deferred1 = async { loader.ensureAvailable(request) }
        val deferred2 = async { loader.ensureAvailable(request) }
        delay(20.milliseconds)
        deferred1.cancel()

        assertTrue(deferred2.await())
    }

    @Test
    fun `new requester rejoins dispatched batch after all previous waiters cancel`() = runTest {
        val request = thumbnailRequest(credentials(), fileId = 40, etag = "etag-40")
        val releaseBatch = CompletableDeferred<Unit>()
        var batchCalls = 0
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)).also(loaderScopes::add)
        val loader = ThumbnailBatchLoader(
            loadBatch = { _, keys ->
                batchCalls++
                releaseBatch.await()
                keys.toSet()
            },
            scope = scope,
            batchSize = 1,
        )
        try {
            val first = async(start = CoroutineStart.UNDISPATCHED) { loader.ensureAvailable(request) }
            assertEquals(1, batchCalls)
            first.cancelAndJoin()
            val next = async(start = CoroutineStart.UNDISPATCHED) { loader.ensureAvailable(request) }
            assertEquals(1, batchCalls)

            releaseBatch.complete(Unit)
            assertTrue(next.await())
            assertEquals(1, batchCalls)
        } finally {
            releaseBatch.complete(Unit)
        }
    }

    @Test
    fun `abandoned requests are dropped when a pending batch fills`() = runTest {
        val loadedIds = mutableListOf<Long>()
        val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler)).also(loaderScopes::add)
        val loader = ThumbnailBatchLoader(
            loadBatch = { _, keys ->
                loadedIds += keys.map { it.fileId }
                keys.toSet()
            },
            scope = scope,
            batchSize = 2,
            batchWindowMillis = 10_000,
        )
        val cancelled = async(start = CoroutineStart.UNDISPATCHED) {
            loader.ensureAvailable(thumbnailRequest(credentials(), 50, "v1"))
        }
        cancelled.cancelAndJoin()
        val remaining = async(start = CoroutineStart.UNDISPATCHED) {
            loader.ensureAvailable(thumbnailRequest(credentials(), 51, "v1"))
        }
        assertTrue(withTimeout(500.milliseconds) { remaining.await() })
        assertEquals(listOf(51L), loadedIds)
    }

    @Test
    fun `thumbnail resolver returns cached file without starting a batch`() = runTest {
        val request = thumbnailRequest(credentials(), fileId = 7, etag = "etag-7")
        val cachedFile = fileFor(request.key).apply { writeText("cached") }
        var ensureCalls = 0

        val resolvedFile = resolveThumbnailFile(
            request = request,
            thumbnailFile = ::fileFor,
            ensureAvailable = {
                ensureCalls += 1
                false
            },
        )

        assertEquals(cachedFile, resolvedFile)
        assertEquals(0, ensureCalls)
    }

    @Test
    fun `thumbnail resolver reads file after successful batch`() = runTest {
        val request = thumbnailRequest(credentials(), fileId = 8, etag = "etag-8")

        val resolvedFile = resolveThumbnailFile(
            request = request,
            thumbnailFile = ::fileFor,
            ensureAvailable = {
                fileFor(it.key).writeText("loaded")
                true
            },
        )

        assertEquals(fileFor(request.key), resolvedFile)
    }

    @Test
    fun `thumbnail resolver returns null when batch cannot provide file`() = runTest {
        val request = thumbnailRequest(credentials(), fileId = 9, etag = "etag-9")

        val resolvedFile = resolveThumbnailFile(
            request = request,
            thumbnailFile = ::fileFor,
            ensureAvailable = { false },
        )

        assertNull(resolvedFile)
    }

    private fun TestScope.loader(
        batchSize: Int = 12,
        batchWindowMillis: Long = 5,
        maxConcurrentBatches: Int = 4,
        loadBatch: suspend (AccountCredentials, List<ThumbnailKey>) -> Set<ThumbnailKey>,
    ): ThumbnailBatchLoader {
        return ThumbnailBatchLoader(
            loadBatch = loadBatch,
            scope = backgroundScope,
            batchWindowMillis = batchWindowMillis,
            batchSize = batchSize,
            maxConcurrentBatches = maxConcurrentBatches,
        )
    }

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }

    private fun fileFor(key: ThumbnailKey): File {
        return File(temporaryFolder.root, "${key.fileId}-${key.etag}.jpg")
    }
}
