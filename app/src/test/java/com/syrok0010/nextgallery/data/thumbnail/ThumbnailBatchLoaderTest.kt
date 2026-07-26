package com.syrok0010.nextgallery.data.thumbnail

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.Duration.Companion.milliseconds

class ThumbnailBatchLoaderTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `concurrent requests are deduplicated and loaded in one batch`() = runBlocking {
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
    fun `large request group is split by multipreview batch size`() = runBlocking {
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
    fun `full batch flushes without waiting for time window`() = runBlocking {
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
    fun `duplicate request joins batch that is already loading`() = runBlocking {
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
    fun `no more than configured number of batches load concurrently`() = runBlocking {
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
    fun `thumbnail resolver returns cached file without starting a batch`() = runBlocking {
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
    fun `thumbnail resolver reads file after successful batch`() = runBlocking {
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
    fun `thumbnail resolver returns null when batch cannot provide file`() = runBlocking {
        val request = thumbnailRequest(credentials(), fileId = 9, etag = "etag-9")

        val resolvedFile = resolveThumbnailFile(
            request = request,
            thumbnailFile = ::fileFor,
            ensureAvailable = { false },
        )

        assertNull(resolvedFile)
    }

    private fun loader(
        batchSize: Int = 12,
        batchWindowMillis: Long = 5,
        maxConcurrentBatches: Int = 4,
        loadBatch: suspend (AccountCredentials, List<ThumbnailKey>) -> Set<ThumbnailKey>,
    ): ThumbnailBatchLoader {
        return ThumbnailBatchLoader(
            loadBatch = loadBatch,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
