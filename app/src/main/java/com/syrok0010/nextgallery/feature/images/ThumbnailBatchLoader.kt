package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class ThumbnailBatchLoader(
    /** Emits each key as soon as its thumbnail is available; absent keys are unavailable. */
    private val loadBatch: (AccountCredentials, List<ThumbnailKey>) -> Flow<ThumbnailKey>,
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
        try {
            mailbox.send(Command.Ensure(request, result))
            return result.await()
        } finally {
            result.cancel()
        }
    }

    private suspend fun processCommands() {
        val pending = mutableMapOf<BatchIdentity, PendingBatch>()
        val inFlight = mutableMapOf<ThumbnailKey, InFlightRequest>()
        var nextBatchId = 0L

        try {
            for (command in mailbox) {
                when (command) {
                    is Command.Ensure -> {
                        if (!command.result.isActive) continue
                        val existingRequest = inFlight[command.request.key]
                        if (existingRequest != null) {
                            existingRequest.waiters += command.result
                            continue
                        }

                        val identity = BatchIdentity(command.request.key)
                        val batch = pending.getOrPut(identity) {
                            PendingBatch(
                                id = nextBatchId++,
                                credentials = command.request.credentials,
                            )
                        }
                        inFlight[command.request.key] = InFlightRequest(
                            batchId = batch.id,
                            waiters = mutableListOf(command.result),
                        )
                        batch.keys += command.request.key

                        if (batch.keys.size >= batchSize) {
                            startBatch(identity, batch, pending, inFlight)
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
                            startBatch(command.identity, batch, pending, inFlight)
                        }
                    }

                    is Command.KeyReady ->
                        complete(inFlight, command.batchId, command.key, available = true)

                    // Keys not reported ready by the end of their batch are unavailable.
                    is Command.BatchCompleted -> command.keys.forEach { key ->
                        complete(inFlight, command.batchId, key, available = false)
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
        inFlight: MutableMap<ThumbnailKey, InFlightRequest>,
    ) {
        if (!pending.remove(identity, batch)) {
            return
        }
        batch.flushJob?.cancel()
        // Drop abandoned requests before dispatch. Dispatched batches finish even
        // without waiters; keeping their in-flight entries lets new callers join.
        val keys = batch.keys.filter { key ->
            val request = inFlight.getValue(key)
            request.waiters.removeAll { !it.isActive }
            if (request.waiters.isEmpty()) {
                inFlight.remove(key)
                false
            } else {
                true
            }
        }
        if (keys.isEmpty()) return

        scope.launch {
            try {
                batchSemaphore.withPermit {
                    loadBatch(batch.credentials, keys).collect { key ->
                        mailbox.send(Command.KeyReady(batch.id, key))
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Keys delivered before the failure stay available; the rest complete as false.
            }
            mailbox.send(Command.BatchCompleted(batch.id, keys))
        }
    }

    /**
     * A key may join a newer batch once an earlier one reported it ready, so completions only
     * apply to the batch that owns the in-flight entry.
     */
    private fun complete(
        inFlight: MutableMap<ThumbnailKey, InFlightRequest>,
        batchId: Long,
        key: ThumbnailKey,
        available: Boolean,
    ) {
        val request = inFlight[key]?.takeIf { it.batchId == batchId } ?: return
        inFlight.remove(key)
        request.waiters.forEach { result -> result.complete(available) }
    }

    private sealed interface Command {
        data class Ensure(
            val request: ThumbnailRequest,
            val result: CompletableDeferred<Boolean>,
        ) : Command

        data class Flush(val identity: BatchIdentity, val batchId: Long) : Command

        data class KeyReady(val batchId: Long, val key: ThumbnailKey) : Command

        data class BatchCompleted(val batchId: Long, val keys: List<ThumbnailKey>) : Command
    }

    private data class BatchIdentity(val width: Int, val height: Int) {
        constructor(key: ThumbnailKey) : this(
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
        val batchId: Long,
        val waiters: MutableList<CompletableDeferred<Boolean>>,
    )

    private companion object {
        const val DEFAULT_BATCH_WINDOW_MILLIS = 60L
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_MAX_CONCURRENT_BATCHES = 4
    }
}
