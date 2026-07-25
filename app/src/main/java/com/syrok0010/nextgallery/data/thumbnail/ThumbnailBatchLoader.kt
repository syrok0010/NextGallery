package com.syrok0010.nextgallery.data.thumbnail

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class ThumbnailBatchLoader(
    private val loadBatch: suspend (AccountCredentials, List<ThumbnailKey>) -> Set<ThumbnailKey>,
    private val scope: CoroutineScope,
    private val batchWindowMillis: Long = DEFAULT_BATCH_WINDOW_MILLIS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    init {
        require(batchWindowMillis >= 0) { "Batch window must not be negative" }
        require(batchSize > 0) { "Batch size must be positive" }
    }

    constructor(
        repository: MemoriesRepository,
    ) : this(
        loadBatch = { credentials, keys ->
            repository.ensureThumbnails(credentials, keys).toSet()
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mutex = Mutex()
    private val pending = mutableMapOf<BatchIdentity, PendingBatch>()
    private val inFlight = mutableMapOf<ThumbnailKey, CompletableDeferred<Boolean>>()

    suspend fun ensureAvailable(request: ThumbnailRequest): Boolean {
        val deferred = mutex.withLock {
            inFlight[request.key]?.let { return@withLock it }

            val identity = BatchIdentity(request.key)
            val existingBatch = pending[identity]
            val batch = existingBatch ?: PendingBatch(request.credentials).also {
                pending[identity] = it
                scope.launch {
                    delay(batchWindowMillis)
                    flush(identity)
                }
            }
            CompletableDeferred<Boolean>().also { result ->
                batch.requests[request.key] = result
                inFlight[request.key] = result
            }
        }

        return deferred.await()
    }

    private suspend fun flush(identity: BatchIdentity) {
        val batch = mutex.withLock { pending.remove(identity) } ?: return
        val readyKeys = buildSet {
            batch.requests.keys.chunked(batchSize).forEach { keys ->
                runCatching { loadBatch(batch.credentials, keys) }
                    .getOrDefault(emptySet())
                    .let(::addAll)
            }
        }

        batch.requests.forEach { (key, result) ->
            mutex.withLock {
                inFlight.remove(key, result)
            }
            result.complete(key in readyKeys)
        }
    }

    private data class BatchIdentity(
        val accountScope: String,
        val width: Int,
        val height: Int,
    ) {
        constructor(key: ThumbnailKey) : this(
            accountScope = key.accountScope,
            width = key.width,
            height = key.height,
        )
    }

    private class PendingBatch(
        val credentials: AccountCredentials,
        val requests: MutableMap<ThumbnailKey, CompletableDeferred<Boolean>> = mutableMapOf(),
    )

    private companion object {
        const val DEFAULT_BATCH_WINDOW_MILLIS = 12L
        const val DEFAULT_BATCH_SIZE = 12
    }
}
