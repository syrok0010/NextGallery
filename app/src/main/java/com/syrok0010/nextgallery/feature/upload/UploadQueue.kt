package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Upload work shown in the app next to the system notification. [pending] holds content URIs
 * submitted and not processed yet; [current] is on the wire. [total] and [processed] count files
 * since the queue was last empty, so a batch submitted meanwhile extends the same progress.
 */
internal data class UploadActivity(
    val pending: Set<String>,
    val total: Int,
    val processed: Int,
    val current: String? = null,
    val percent: Int? = null,
)

/** Results of service passes the app has not shown yet; [failures] are already localized. */
internal data class UploadResult(
    val uploaded: Int,
    val files: Int,
    val failures: List<String> = emptyList(),
    val details: List<UiText> = emptyList(),
    val existing: Int = 0,
    val cancelled: Int = 0,
    val conflicts: Int = 0,
    val attention: Int = 0,
) {
    operator fun plus(other: UploadResult) =
        UploadResult(
            uploaded + other.uploaded,
            files + other.files,
            failures + other.failures,
            details + other.details,
            existing + other.existing,
            cancelled + other.cancelled,
            conflicts + other.conflicts,
            attention + other.attention,
        )
}


/** UI and foreground executor cross this protocol; Room owns cancellation and receipt acceptance. */
internal class UploadQueue(
    private val sessions: SessionStore,
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val journal: UploadJournal,
    private val snapshot: (UploadSelection) -> SourceStamp,
    private val link: suspend (AccountCredentials, String, Long) -> Boolean,
    private val album: suspend (AccountCredentials, UploadIntent, UploadedFile, suspend () -> Boolean) -> UploadStepResult,
    private val refreshLibrary: () -> Unit,
    private val processId: String = uploadProcessId,
    private val prepareAlbum: suspend (AccountCredentials, UploadIntent) -> UploadIntent = { _, intent -> intent },
) {
    private val commands = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private var available = false
    private var owner: String? = null
    private var records = emptyList<UploadJob>()
    private val items = mutableMapOf<String, UploadItem>()
    private val batchCache = mutableMapOf<String, UploadBatch>()
    private val released = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val started = ArrayDeque<UploadBatch>()
    private val mutableWaiting = MutableStateFlow<List<UploadBatch>>(emptyList())
    val waiting = mutableWaiting.asStateFlow()
    private val mutableBatches = MutableStateFlow<List<UploadBatch>>(emptyList())
    val batches = mutableBatches.asStateFlow()
    private val mutableActivity = MutableStateFlow<UploadActivity?>(null)
    val activity = mutableActivity.asStateFlow()
    private val mutableResult = MutableStateFlow<UploadResult?>(null)
    val result = mutableResult.asStateFlow()
    private val mutableError = MutableStateFlow(false)
    val storageError = mutableError.asStateFlow()

    init {
        scope.launch {
            sessions.session.collectLatest { session ->
                val token = (session as? SessionUiState.SignedIn)?.credentials?.uploadOwner
                commands.withLock {
                    items.values.forEach(UploadItem::stopCall)
                    items.clear(); batchCache.clear(); released.clear(); started.clear()
                    owner = token
                    available = false
                    try {
                        journal.activate(token)
                        if (token != null) journal.recover(token, processId)
                        available = true
                        project(if (token != null) journal.jobs(token).filterNot { it.hidden } else emptyList())
                        mutableError.value = false
                    } catch (_: Exception) {
                        currentCoroutineContext().ensureActive()
                        available = false
                        mutableError.value = true
                        project(emptyList())
                    } finally { ready.complete(Unit) }
                }
                if (token != null && available) {
                    journal.observe(token).catch { mutableError.value = true }.collect {
                        commands.withLock { if (owner == token) project(journal.jobs(token).filterNot { it.hidden }) }
                    }
                }
            }
        }
    }

    /** Rejection leaves selection intact. Caller retains requestId when retrying an uncertain reply. */
    suspend fun submit(mediaIds: Collection<MediaId>, intents: List<UploadIntent> = emptyList(), requestId: String = UUID.randomUUID().toString()): EnqueueResult {
        ready.await()
        return commands.withLock {
            val account = credentials() ?: return@withLock EnqueueResult.Rejected
            if (!available || owner != account.uploadOwner) return@withLock EnqueueResult.Rejected
            try {
                journal.prior(account.uploadOwner, requestId)?.let { return@withLock it }
                val index = library.state.value
                val files = mediaIds.distinct().mapNotNull { id -> index[id]?.let(::uploadSelection) }
                if (files.isEmpty()) return@withLock EnqueueResult.NothingToUpload
                // Missing sources remain durable attention entries; never silently change the selection.
                val snapshots = withContext(Dispatchers.IO) {
                    files.map { file -> file.copy(stamp = try { snapshot(file) } catch (_: Exception) { null }) }
                }
                if (credentials()?.uploadOwner != account.uploadOwner) return@withLock EnqueueResult.Rejected
                val prepared = intents.map { if (it.kind == "album") prepareAlbum(account, it) else it }
                if (!current(account)) return@withLock EnqueueResult.Rejected
                val result = journal.enqueue(account.uploadOwner, requestId, snapshots, listOf(UploadIntent("link")) + prepared)
                project(journal.jobs(account.uploadOwner).filterNot { it.hidden })
                mutableError.value = result == EnqueueResult.Rejected
                result
            } catch (_: Exception) { currentCoroutineContext().ensureActive(); mutableError.value = true; EnqueueResult.Rejected }
        }
    }

    fun retryActions(item: UploadItem) = command { token -> journal.retrySteps(token, item.id) }
    fun cancelAll() = command { token -> journal.cancel(token) }
    fun cancel(batch: UploadBatch) = command { token -> journal.cancel(token, batch.items.map { it.id }.toSet()) }
    fun cancel(item: UploadItem) = command { token -> journal.cancel(token, setOf(item.id)) }
    fun clearFinished() = command { token -> journal.clearFinished(token) }
    fun consumeResult() = command { token -> journal.seen(token) }

    private fun command(action: suspend (String) -> Unit) {
        scope.launch {
            ready.await()
            commands.withLock {
                val token = credentials()?.uploadOwner ?: return@withLock
                try {
                    action(token)
                    val rows = journal.jobs(token).filterNot { it.hidden }
                    // Durable commit precedes best-effort socket cancellation.
                    rows.filter { it.state == "Cancelled" }.forEach { items[it.id]?.stopCall() }
                    project(rows)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (_: Exception) { mutableError.value = true }
            }
        }
    }

    /** Blocks enqueue while checking the durable predicate and revoking the login lifetime. */
    suspend fun logout(clearSession: () -> Unit): Boolean {
        ready.await()
        return commands.withLock {
            val token = credentials()?.uploadOwner ?: return@withLock false
            try {
                if (!available || !journal.revokeIfFinished(token)) return@withLock false
                items.values.forEach(UploadItem::stopCall)
                try { clearSession() } catch (error: Exception) {
                    // A failed secure-store clear keeps the signed-in owner usable for retry.
                    journal.activate(token)
                    throw error
                }
                true
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { mutableError.value = true; false }
        }
    }

    @Synchronized fun release(): List<UploadBatch> {
        val result = mutableWaiting.value
        mutableWaiting.value = emptyList()
        result.forEach { released += it.id; started.addLast(it) }
        return result
    }
    @Synchronized fun restore(batches: List<UploadBatch>) {
        batches.forEach { if (started.remove(it)) released.remove(it.id) }
        project(records)
    }
    @Synchronized fun next(): UploadBatch? = if (started.isEmpty()) null else started.removeFirst()
    @Synchronized fun releasedFiles() = started.sumOf { it.files.size }

    fun enterForeground(failureReason: String, startForeground: () -> Unit): Boolean {
        try { startForeground(); return true } catch (_: RuntimeException) {
            val refused = generateSequence { next() }.toList()
            command { token ->
                refused.flatMap { it.items }.forEach { item ->
                    val claim = journal.claim(item.id, token, processId)
                    if (claim != null) {
                        if (claim.receiptJson == null) journal.problem(claim, UploadProblem.Foreground)
                        else {
                            if (claim.linkStep == "Pending") journal.step(claim, "link", StepState.Attention)
                            if (claim.albumStep == "Pending") journal.step(claim, "album", StepState.Attention)
                            journal.release(claim)
                        }
                    }
                }
                refused.forEach { released.remove(it.id) }
            }
            return false
        }
    }

    /** Existing executor supplies conditional transfer; journal selects transfer vs receipt replay. */
    suspend fun runBatch(batch: UploadBatch, transfer: suspend (AccountCredentials, UploadItem, UploadJob) -> UploadedFile?) {
        val account = credentials() ?: return
        try {
            runClaimedBatch(account, batch, transfer)
        } catch (cancelled: CancellationException) {
            stopExecutor(account, batch)
            throw cancelled
        }
    }

    private suspend fun runClaimedBatch(account: AccountCredentials, batch: UploadBatch, transfer: suspend (AccountCredentials, UploadItem, UploadJob) -> UploadedFile?) {
        var changed = false
        for (item in batch.items) {
            val id = item.id
            var claim = journal.claim(id, account.uploadOwner, processId) ?: continue
            try {
                if (!current(account)) continue
                val intents = try { claim.intents } catch (_: Exception) { emptyList() }
                if (intents.isEmpty() || intents.any { (it.kind == "link" && it.version != 1) || (it.kind == "album" && it.version !in setOf(1, 2)) || it.kind !in setOf("link", "album") }) {
                    if (claim.receiptJson == null) journal.problem(claim, UploadProblem.Format)
                    else {
                        journal.step(claim, "link", StepState.Attention, "Format")
                        journal.step(claim, "album", StepState.Attention, "Format")
                    }
                    continue
                }
                if (claim.receiptJson == null) {
                    if (!validSource(item.selection)) { journal.problem(claim, UploadProblem.Source); continue }
                    val uploaded = try { transfer(account, item, claim) } catch (cancelled: CancellationException) { throw cancelled
                    } catch (error: Exception) {
                        val problem = when ((error as? UploadException)?.reason) {
                            R.string.upload_error_directory -> UploadProblem.Directory
                            R.string.upload_error_http -> UploadProblem.Http
                            else -> UploadProblem.Transfer
                        }
                        journal.problem(claim, problem, httpStatus = (error as? UploadException)?.args?.firstOrNull() as? Int)
                        continue
                    }
                    if (uploaded == null) { journal.problem(claim, UploadProblem.Transfer, conflict = true); continue }
                    // Source may change while streaming. Do not accept/link different observed bytes.
                    if (!validSource(item.selection)) { journal.problem(claim, UploadProblem.Source); continue }
                    if (!current(account)) continue
                    claim = journal.accept(claim, UploadReceipt(uploaded.url.toString(), uploaded.created, uploaded.fileId)) ?: continue
                }
                val receipt = claim.receipt ?: continue
                if (receipt.version != 1) {
                    journal.step(claim, "link", StepState.Attention, "Format")
                    journal.step(claim, "album", StepState.Attention, "Format")
                    continue
                }
                if (claim.linkStep == "Pending" && current(account)) {
                    claim = journal.beginStep(claim, "link") ?: continue
                    val result = try {
                        val linked = journal.stepCurrent(claim, "link") && current(account) &&
                            receipt.fileId?.let { link(account, item.selection.identityUri, it) } == true
                        if (linked) UploadStepResult("Done", fileId = receipt.fileId, href = item.selection.identityUri)
                        else UploadStepResult.attention("Link")
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { UploadStepResult.attention("Link") }
                    claim = journal.finishStep(claim, "link", result) ?: continue
                }
                if (claim.albumStep == "Pending" && current(account)) {
                    claim = journal.beginStep(claim, "album") ?: continue
                    val intent = intents.singleOrNull { it.kind == "album" }
                    val result = if (intent == null || intent.version != 2 || intent.albumId == null || intent.albumHref == null) {
                        UploadStepResult.attention("LegacyTarget")
                    } else try {
                        album(account, intent, UploadedFile(item.selection, receipt.url.toHttpUrl(), receipt.created, receipt.fileId)) {
                            current(account) && journal.stepCurrent(claim, "album")
                        }
                    } catch (cancelled: CancellationException) { throw cancelled
                    } catch (_: Exception) { UploadStepResult.uncertain() }
                    claim = journal.finishStep(claim, "album", result) ?: continue
                }
                changed = true
            } catch (cancelled: CancellationException) {
                item.stopCall()
                throw cancelled
            } catch (_: Exception) {
                if (claim.receiptJson == null) journal.problem(claim, UploadProblem.Transfer)
                else {
                    if (claim.linkStep in setOf("Pending", "Running")) journal.step(claim, "link", StepState.Attention)
                    if (claim.albumStep in setOf("Pending", "Running")) journal.step(claim, "album", StepState.Attention)
                }
            } finally {
                // On abrupt death this block does not run; startup fences its persisted claim.
                if (claim.receiptJson != null) changed = true
                if (currentCoroutineContext().isActive) journal.release(claim)
                commands.withLock { if (current(account)) project(journal.jobs(account.uploadOwner).filterNot { it.hidden }) }
            }
        }
        if (changed && current(account)) refreshLibrary()
        commands.withLock {
            released.remove(batch.id)
            if (current(account)) project(journal.jobs(account.uploadOwner).filterNot { it.hidden })
        }
    }

    /** Graceful stop fences the whole handed-off pass; Attention prevents an immediate start loop. */
    suspend fun stopExecutor() {
        val account = credentials() ?: return
        stopExecutor(account, null)
    }

    private suspend fun stopExecutor(account: AccountCredentials, currentBatch: UploadBatch?) = withContext(NonCancellable) {
        commands.withLock {
            if (owner != account.uploadOwner || !current(account)) return@withLock
            val batches = synchronized(this@UploadQueue) { released.toSet() + listOfNotNull(currentBatch?.id) }
            journal.interrupt(account.uploadOwner, batches, processId)
            synchronized(this@UploadQueue) {
                started.removeAll { it.id in batches }
                released.removeAll(batches)
                records.filter { it.batchId in batches }.forEach { items[it.id]?.stopCall() }
            }
            if (current(account)) project(journal.jobs(account.uploadOwner).filterNot { it.hidden })
        }
    }

    suspend fun pinDestination(claim: UploadJob, url: String): String? = journal.destination(claim, url)?.destination
    private fun current(account: AccountCredentials) = credentials()?.uploadOwner == account.uploadOwner
    private fun credentials() = (sessions.session.value as? SessionUiState.SignedIn)?.credentials
    private suspend fun validSource(file: UploadSelection): Boolean = withContext(Dispatchers.IO) {
        file.stamp != null && try { snapshot(file) == file.stamp } catch (_: Exception) { false }
    }

    fun uploading(contentUri: String, percent: Int?) {
        mutableActivity.update { it?.copy(current = uploadedLocalIdentityUri(contentUri), percent = percent) }
    }

    @Synchronized private fun project(rows: List<UploadJob>) {
        records = rows
        rows.forEach { row ->
            val item = items.getOrPut(row.id) { UploadItem(row.selection, row.id) }
            val attention = row.hasAttention
            val phase = when (row.state) {
                "Pending", "Uncertain" -> UploadPhase.Waiting
                "Running" -> UploadPhase.Uploading
                "Accepted" -> if (row.receipt?.created == true) UploadPhase.Uploaded else UploadPhase.Existing
                "Conflict" -> UploadPhase.NameConflict
                "Cancelled" -> UploadPhase.Cancelled
                else -> UploadPhase.NeedsAttention
            }
            val old = item.state.value
            item.restore(UploadItemState(phase, if (phase == UploadPhase.Uploading) old.percent else null,
                failure = row.problem, pendingAction = row.receiptJson != null && row.unfinished, attention = attention,
                actionSuppressed = row.linkStep == "Suppressed" || row.albumStep == "Suppressed",
                retryActions = row.receiptJson != null && row.executor == null && row.hasAttention,
                linkProblem = row.linkResultJson?.takeIf { row.linkStep in setOf("Attention", "Uncertain") }?.let { uploadJson.decodeFromString<UploadStepResult>(it).problem },
                albumProblem = row.albumResultJson?.takeIf { row.albumStep in setOf("Attention", "Uncertain") }?.let { uploadJson.decodeFromString<UploadStepResult>(it).problem },
                albumAdded = if (row.albumStep == "Done") row.intents.firstOrNull { it.kind == "album" }?.target else null, httpStatus = row.httpStatus))
        }
        val batches = rows.groupBy { it.batchId }.map { (id, jobs) ->
            batchCache.getOrPut(id) { UploadBatch(jobs.map { it.selection }, id = id, items = jobs.map { items.getValue(it.id) }) }
        }
        mutableBatches.value = batches
        mutableWaiting.value = batches.filter { batch -> batch.id !in released && rows.any { it.batchId == batch.id && it.runnable } }
        val activePasses = rows.filter { it.runnable || it.executor != null }.map { it.passId }.toSet()
        val passRows = rows.filter { it.passId in activePasses }
        val runnable = passRows.filter { it.runnable || it.executor != null }
        val progress = mutableActivity.value
        val current = progress?.current?.takeIf { uri ->
            runnable.any { it.state == "Running" && it.executor != null && it.selection.identityUri == uri }
        }
        mutableActivity.value = if (runnable.isEmpty()) null else UploadActivity(
            pending = runnable.map { it.selection.identityUri }.toSet(), total = passRows.size,
            processed = passRows.size - runnable.size, current = current,
            percent = progress?.percent?.takeIf { current != null },
        )
        val summaries = rows.filter { !it.seen && it.hasResult }
        mutableResult.value = if (summaries.isEmpty()) null else UploadResult(
            uploaded = summaries.count { it.receiptJson != null }, files = summaries.size,
            attention = summaries.count { it.hasAttention },
            existing = summaries.count { it.receipt?.created == false }, cancelled = summaries.count { it.state == "Cancelled" }, conflicts = summaries.count { it.state == "Conflict" },
        )
    }

}

private val uploadProcessId = UUID.randomUUID().toString()
