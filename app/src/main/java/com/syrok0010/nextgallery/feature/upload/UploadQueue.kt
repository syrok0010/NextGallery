package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manual uploads. Room keeps every job, so a queue survives the process; the screen, the status
 * pill and the notification all show projections of the journal. [UploadWorker] runs the jobs
 * through [drain]. Accepted and retried jobs call [schedule]; a temporary failure leaves the
 * next attempt to the worker's own backoff.
 */
internal class UploadQueue(
    private val sessions: SessionStore,
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val journal: UploadJournal,
    private val snapshot: (UploadSelection) -> SourceStamp,
    private val followUps: UploadFollowUps,
    private val refreshLibrary: () -> Unit,
    private val processId: String = UUID.randomUUID().toString(),
    private val schedule: suspend () -> Unit = {},
) : OperationStatusSource {
    private val commands = Mutex()

    /** WorkManager does not wait for a stopped worker's cleanup before it starts the next one. */
    private val passes = Mutex()
    private val rows = MutableStateFlow<List<UploadJob>>(emptyList())
    private val progress = MutableStateFlow<Progress?>(null)
    private val running = ConcurrentHashMap<String, Job>()

    val entries: StateFlow<List<UploadEntry>> =
        combine(rows, progress, ::uploadEntries).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Ids of the current pass: a file queued while others run joins it until none is active. */
    private val pass =
        rows.runningFold(emptyList<UploadJob>() to emptySet<String>()) { (_, ids), jobs ->
            val active = jobs.filter { it.active }.mapTo(HashSet()) { it.id }
            jobs to if (active.isEmpty()) emptySet() else ids + active
        }
    val activity: StateFlow<UploadActivity?> =
        combine(pass, progress) { (jobs, ids), progress -> uploadActivity(jobs, ids, progress) }
            .stateIn(scope, SharingStarted.Eagerly, null)
    val result: StateFlow<UploadResult?> =
        rows.map(::uploadResult).stateIn(scope, SharingStarted.Eagerly, null)
    override val operations =
        combine(activity, result, ::uploadOperationSnapshots)
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            sessions.session
                .map { (it as? SessionUiState.SignedIn)?.credentials?.uploadOwner }
                .distinctUntilChanged()
                .collectLatest { owner ->
                    rows.value = emptyList()
                    if (owner == null) return@collectLatest
                    // Claims of an earlier process died with it.
                    journal.release(owner) { it != processId }
                    // Covers a process that died between accepting jobs and scheduling them.
                    if (journal.jobs(owner).any { it.waiting }) scheduleWorker()
                    journal.observe(owner).collect { rows.value = it }
                }
        }
    }

    /** Queues the phone-only objects among [mediaIds]; [album] gets them once they are uploaded. */
    suspend fun submit(mediaIds: Collection<MediaId>, album: UploadAlbum? = null): EnqueueResult =
        commands.withLock {
            val owner = owner() ?: return@withLock EnqueueResult.Rejected
            val index = library.state.value
            val files = mediaIds.distinct().mapNotNull { id -> index[id]?.let(::uploadSelection) }
            if (files.isEmpty()) return@withLock EnqueueResult.NothingToUpload
            // A file that cannot be read now is still queued and then needs attention.
            val stamped = withContext(Dispatchers.IO) { files.map { it.copy(stamp = stamp(it)) } }
            journal.enqueue(owner, stamped, album).also {
                if (it is EnqueueResult.Accepted) scheduleWorker()
            }
        }

    fun cancelAll() =
        command { owner ->
            journal.cancel(owner)
            running.values.forEach(Job::cancel)
        }

    fun cancel(id: String) =
        command { owner ->
            // The journal decides first, so a response arriving meanwhile cannot be accepted.
            journal.cancel(owner, setOf(id))
            running[id]?.cancel()
        }

    /** Re-runs link/album reconciliation without reading the source or issuing another PUT. */
    fun retry(id: String) =
        command { owner ->
            journal.retry(owner, setOf(id))
            scheduleWorker()
        }

    fun clearFinished() = command(journal::clearFinished)

    fun consumeResult() = command(journal::seen)

    override fun consumeSucceeded() {
        if (result.value?.failed == false) consumeResult()
    }

    override fun consumeAll() = consumeResult()

    /** Signs out through [clearSession] unless some upload work is unfinished. */
    suspend fun logout(clearSession: () -> Unit): Boolean =
        commands.withLock {
            val owner = owner() ?: return@withLock false
            try {
                if (!journal.clearIfFinished(owner)) return@withLock false
                clearSession()
                true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
        }

    /**
     * Runs jobs one by one until none is waiting. [put] transfers the bytes and reports percent;
     * it returns null when every free name holds a different file. Returns true when a job failed
     * temporarily and waits for another attempt.
     */
    suspend fun drain(
        put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile?,
    ): Boolean =
        passes.withLock {
            val account = credentials() ?: return@withLock false
            val owner = account.uploadOwner
            var reachedServer = false
            val deferred = HashSet<String>()
            try {
                // Passes never overlap, so every claim left now is stale: a dead process, or an
                // earlier pass here that could not release its own.
                journal.release(owner) { true }
                while (credentials() == account) {
                    val claim = journal.claimNext(owner, processId, deferred) ?: break
                    reachedServer = runJob(account, claim, deferred, put) || reachedServer
                }
                deferred.isNotEmpty()
            } finally {
                withContext(NonCancellable) {
                    progress.value = null
                    journal.release(owner) { it == processId }
                }
                if (reachedServer) refreshLibrary()
            }
        }

    /** Read from the journal, so it already counts the job [drain] finished last. */
    suspend fun unseenResult(): UploadResult? =
        owner()?.let { owner -> uploadResult(journal.jobs(owner).filterNot { it.hidden }) }

    /** The original name the job keeps across restarts, pinned on its first attempt. */
    suspend fun pin(claim: UploadJob, path: DavFilePath): DavFilePath =
        journal.pin(claim, path.segments)?.destination?.let { DavFilePath(path.user, it) }
            ?: throw IOException("Upload no longer claimed")

    /** True when the file reached the server. A job cancelled by the user ends quietly. */
    private suspend fun runJob(
        account: AccountCredentials,
        claim: UploadJob,
        deferred: MutableSet<String>,
        put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile?,
    ): Boolean =
        coroutineScope {
            val work = async { execute(account, claim, put) { deferred += claim.id } }
            running[claim.id] = work
            try {
                work.await()
            } catch (cancelled: CancellationException) {
                ensureActive()
                false
            } finally {
                running.remove(claim.id)
                progress.value = null
            }
        }

    /** [defer] is called when the job gave up on a temporary failure. */
    private suspend fun execute(
        account: AccountCredentials,
        start: UploadJob,
        put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile?,
        defer: () -> Unit,
    ): Boolean {
        var claim = start
        var acceptedNow = false
        if (claim.receipt == null) {
            claim = transfer(account, claim, put, defer) ?: return false
            acceptedNow = true
        }
        val receipt = checkNotNull(claim.receipt)
        val file =
            UploadedFile(
                claim.selection,
                DavFilePath(account.userId, receipt.path),
                receipt.created,
                receipt.fileId,
            )
        for (step in FollowUp.entries) {
            if (claim.steps[step] != StepState.Pending) continue
            val state = attempt { followUps[step].run(account, claim, file, !acceptedNow) }
            if (state == null) {
                journal.defer(claim)
                defer()
                return true
            }
            claim = journal.step(claim, step, state) ?: return true
        }
        journal.finish(claim)
        return true
    }

    /** Returns the accepted job, or null when the file did not reach the server as this job. */
    private suspend fun transfer(
        account: AccountCredentials,
        claim: UploadJob,
        put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile?,
        defer: () -> Unit,
    ): UploadJob? {
        if (!sourceUnchanged(claim.selection)) return problem(claim, UploadProblem.Source)
        val uploaded = try {
            put(account, claim) { percent -> progress.value = Progress(claim.id, percent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.temporary) {
                journal.defer(claim)
                defer()
                return null
            }
            val failure = error as? UploadException
            return problem(claim, failure?.problem ?: UploadProblem.Unexpected, failure?.httpStatus)
        }
        if (uploaded == null) return problem(claim, null, conflict = true)
        // The file may change while it is read; then the server copy is not this phone file.
        if (!sourceUnchanged(claim.selection)) return problem(claim, UploadProblem.Source)
        return journal.accept(
            claim,
            UploadReceipt(uploaded.path.segments, uploaded.created, uploaded.fileId),
        )
    }

    /** Leaves the job to the user; null, so [transfer] returns it. */
    private suspend fun problem(
        claim: UploadJob,
        problem: UploadProblem?,
        httpStatus: Int? = null,
        conflict: Boolean = false,
    ): UploadJob? {
        journal.problem(claim, problem, conflict, httpStatus)
        return null
    }

    private fun command(action: suspend (String) -> Unit) {
        scope.launch {
            val owner = owner() ?: return@launch
            try {
                action(owner)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The journal is unchanged; the user can repeat the action.
            }
        }
    }

    private suspend fun scheduleWorker() {
        try {
            schedule()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // The jobs stay in the journal; the next start of the app schedules them.
        }
    }

    private fun stamp(file: UploadSelection): SourceStamp? =
        try {
            snapshot(file)
        } catch (_: Exception) {
            null
        }

    private suspend fun sourceUnchanged(file: UploadSelection): Boolean =
        file.stamp != null && withContext(Dispatchers.IO) { stamp(file) } == file.stamp

    private fun credentials() = (sessions.session.value as? SessionUiState.SignedIn)?.credentials

    private fun owner() = credentials()?.uploadOwner
}

/** Percent of the file on the wire; null while its size is unknown. */
internal data class Progress(val id: String, val percent: Int?)

/** Done or Attention; null when the step failed temporarily and should run again later. */
private suspend fun attempt(action: suspend () -> Boolean): StepState? =
    try {
        if (action()) StepState.Done else StepState.Attention
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error.temporary) null else StepState.Attention
    }
