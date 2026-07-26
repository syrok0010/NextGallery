package com.syrok0010.nextgallery.data.thumbnail

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MemoriesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Duration.Companion.milliseconds

internal class ThumbnailBatchLoader(
    private val loadBatch: suspend (AccountCredentials, List<ThumbnailKey>) -> Set<ThumbnailKey>,
    private val scope: CoroutineScope,
    private val batchWindowMillis: Long = DEFAULT_BATCH_WINDOW_MILLIS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    maxConcurrentBatches: Int = DEFAULT_MAX_CONCURRENT_BATCHES,
) {
    init {
        require(batchWindowMillis >= 0) { "Batch window must not be negative" }
        require(batchSize > 0) { "Batch size must be positive" }
        require(maxConcurrentBatches > 0) { "Maximum concurrent batches must be positive" }
    }

    private val batchSemaphore = Semaphore(maxConcurrentBatches)

    constructor(
        repository: MemoriesRepository,
    ) : this(
        loadBatch = { credentials, keys ->
            repository.ensureThumbnails(credentials, keys).toSet()
        },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    private val mailbox = Channel<Command>(
        capacity = Channel.BUFFERED,
        onUndeliveredElement = { command ->
            if (command is Command.Ensure) {
                command.result.complete(false)
            }
        },
    )

    init {
        scope.launch {
            processCommands()
        }
    }

    suspend fun ensureAvailable(request: ThumbnailRequest): Boolean {
        val result = CompletableDeferred<Boolean>()
        mailbox.send(Command.Ensure(request, result))
        return result.await()
    }

    private suspend fun processCommands() {
        val pending = mutableMapOf<BatchIdentity, PendingBatch>()
        val inFlight = mutableMapOf<ThumbnailKey, InFlightRequest>()
        var nextBatchId = 0L

        try {
            for (command in mailbox) {
                when (command) {
                    is Command.Ensure -> {
                        val existingRequest = inFlight[command.request.key]
                        if (existingRequest != null) {
                            existingRequest.waiters += command.result
                            continue
                        }

                        inFlight[command.request.key] = InFlightRequest(
                            waiters = mutableListOf(command.result),
                        )
                        val identity = BatchIdentity(command.request.key)
                        val batch = pending.getOrPut(identity) {
                            PendingBatch(
                                id = nextBatchId++,
                                credentials = command.request.credentials,
                            )
                        }
                        batch.keys += command.request.key

                        if (batch.keys.size >= batchSize) {
                            startBatch(identity, batch, pending)
                        } else if (batch.flushJob == null) {
                            batch.flushJob = scope.launch {
                                delay(batchWindowMillis.milliseconds)
                                mailbox.send(Command.Flush(identity, batch.id))
                            }
                        }
                    }

                    is Command.Flush -> {
                        val batch = pending[command.identity]
                        if (batch?.id == command.batchId) {
                            startBatch(command.identity, batch, pending)
                        }
                    }

                    is Command.BatchCompleted -> {
                        command.keys.forEach { key ->
                            val request = inFlight.remove(key) ?: return@forEach
                            val available = key in command.readyKeys
                            request.waiters.forEach { result ->
                                result.complete(available)
                            }
                        }
                    }
                }
            }
        } finally {
            mailbox.cancel()
            pending.values.forEach { batch -> batch.flushJob?.cancel() }
            inFlight.values
                .flatMap(InFlightRequest::waiters)
                .forEach { result -> result.complete(false) }
        }
    }

    private fun startBatch(
        identity: BatchIdentity,
        batch: PendingBatch,
        pending: MutableMap<BatchIdentity, PendingBatch>,
    ) {
        if (!pending.remove(identity, batch)) {
            return
        }
        batch.flushJob?.cancel()
        val keys = batch.keys.toList()

        scope.launch {
            val readyKeys = try {
                batchSemaphore.withPermit {
                    loadBatch(batch.credentials, keys)
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                emptySet()
            }
            mailbox.send(
                Command.BatchCompleted(
                    keys = keys,
                    readyKeys = readyKeys,
                ),
            )
        }
    }

    private sealed interface Command {
        data class Ensure(
            val request: ThumbnailRequest,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data class Flush(
            val identity: BatchIdentity,
            val batchId: Long,
        ) : Command

        data class BatchCompleted(
            val keys: List<ThumbnailKey>,
            val readyKeys: Set<ThumbnailKey>,
        ) : Command
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
        val id: Long,
        val credentials: AccountCredentials,
        val keys: MutableSet<ThumbnailKey> = linkedSetOf(),
        var flushJob: Job? = null,
    )

    private class InFlightRequest(
        val waiters: MutableList<CompletableDeferred<Boolean>>,
    )

    private companion object {
        const val DEFAULT_BATCH_WINDOW_MILLIS = 20L
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_CONCURRENT_BATCHES = 4
    }
}
