package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.operation.OperationStatusSource
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class UploadPhase { Waiting, Uploading, Uploaded, Existing, NameConflict, NeedsAttention, Cancelled }

/** One file on the uploads screen. */
internal data class UploadEntry(
    val id: String,
    val selection: UploadSelection,
    val phase: UploadPhase,
    val percent: Int? = null,
    /** Set while the file needs the user, also after upload when a follow-up failed. */
    val problem: UiText? = null,
    /** Unfinished work blocks logout, so the user can always cancel it. */
    val cancellable: Boolean = false,
    /** A receipt exists and a follow-up can be retried without another upload. */
    val retryable: Boolean = false,
)

/**
 * Upload work shown next to the system notification. [pending] holds identity URIs of files not
 * done yet; [current] is on the wire. [total] and [processed] count files of the current pass, so
 * a file queued meanwhile extends the same progress.
 */
internal data class UploadActivity(
    val pending: Set<String>,
    val total: Int,
    val processed: Int,
    val current: String? = null,
    val percent: Int? = null,
)

/** Results the user has not looked at yet. */
internal data class UploadResult(
    val files: Int,
    val uploaded: Int,
    val existing: Int = 0,
    val cancelled: Int = 0,
    val conflicts: Int = 0,
    val problems: List<UiText> = emptyList(),
) {
    val failed: Boolean get() = problems.isNotEmpty() || conflicts > 0
}

/**
 * Manual uploads. Room keeps every job, so a queue survives the process; the screen, the status
 * pill and the notification all show projections of the journal. [UploadWorker] runs the jobs
 * through [drain]; each newly waiting job calls [schedule] to make sure a worker will take it.
 */
internal class UploadQueue(
    private val sessions: SessionStore,
    private val library: MediaLibraryIndex,
    private val scope: CoroutineScope,
    private val journal: UploadJournal,
    private val snapshot: (UploadSelection) -> SourceStamp,
    /**
     * Runs one step for [UploadJob] whose [UploadedFile] is on the server. `recovered` is false
     * only for the executor that accepted the file just now; a later attempt may follow one whose
     * outcome is unknown, so a step that is not safe to repeat checks the server first.
     */
    private val followUp: suspend (FollowUp, AccountCredentials, UploadJob, UploadedFile, Boolean) -> Boolean,
    private val refreshLibrary: () -> Unit,
    private val processId: String = UUID.randomUUID().toString(),
    private val schedule: suspend () -> Unit = {},
) : OperationStatusSource {
    private val commands = Mutex()
    private val rows = MutableStateFlow<List<UploadJob>>(emptyList())
    private val progress = MutableStateFlow<Progress?>(null)
    private val running = ConcurrentHashMap<String, Job>()

    val entries: StateFlow<List<UploadEntry>> =
        combine(rows, progress, ::uploadEntries).stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Jobs nobody runs yet. */
    val waiting: StateFlow<Set<String>> =
        rows.map { jobs -> jobs.filter { it.runnable && it.executor == null }.mapTo(HashSet()) { it.id } }
            .stateIn(scope, SharingStarted.Eagerly, emptySet())

    /** Ids of the current pass: a file queued while others run joins it until none is active. */
    private val pass = rows.runningFold(emptyList<UploadJob>() to emptySet<String>()) { (_, ids), jobs ->
        val active = jobs.filter(::active).mapTo(HashSet()) { it.id }
        jobs to if (active.isEmpty()) emptySet() else ids + active
    }
    val activity: StateFlow<UploadActivity?> =
        combine(pass, progress) { (jobs, ids), progress -> uploadActivity(jobs, ids, progress) }
            .stateIn(scope, SharingStarted.Eagerly, null)
    val result: StateFlow<UploadResult?> = rows.map(::uploadResult).stateIn(scope, SharingStarted.Eagerly, null)
    override val operations =
        combine(activity, result, ::uploadOperationSnapshots).stateIn(scope, SharingStarted.Eagerly, emptyList())

    init {
        scope.launch {
            sessions.session.map { (it as? SessionUiState.SignedIn)?.credentials?.uploadOwner }.distinctUntilChanged().collectLatest { owner ->
                rows.value = emptyList()
                if (owner == null) return@collectLatest
                // Claims of an earlier process died with it.
                journal.release(owner) { it != processId }
                journal.observe(owner).collect { rows.value = it }
            }
        }
        scope.launch {
            // A job that waits again, after a retry or a stop, schedules once more as well.
            var previous = emptySet<String>()
            waiting.collect { ids ->
                if (!previous.containsAll(ids)) {
                    try {
                        schedule()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // The jobs stay in the journal; the next start of the app schedules them.
                    }
                }
                previous = ids
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
            journal.enqueue(owner, stamped, album)
        }

    fun cancelAll() = command { owner ->
        journal.cancel(owner)
        running.values.forEach(Job::cancel)
    }

    fun cancel(id: String) = command { owner ->
        // The journal decides first, so a response arriving meanwhile cannot be accepted.
        journal.cancel(owner, setOf(id))
        running[id]?.cancel()
    }

    /** Re-runs link/album reconciliation without reading the source or issuing another PUT. */
    fun retry(id: String) = command { owner -> journal.retry(owner, setOf(id)) }

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
    suspend fun drain(put: suspend (AccountCredentials, UploadJob, (Int?) -> Unit) -> UploadedFile?): Boolean {
        val account = credentials() ?: return false
        val owner = account.uploadOwner
        var reachedServer = false
        val deferred = HashSet<String>()
        try {
            // One process runs uploads, so any other claim belongs to a process that died.
            journal.release(owner) { it != processId }
            while (credentials() == account) {
                val claim = journal.claimNext(owner, processId, deferred) ?: break
                reachedServer = runJob(account, claim, deferred, put) || reachedServer
            }
            return deferred.isNotEmpty()
        } finally {
            withContext(NonCancellable) {
                progress.value = null
                journal.release(owner) { it == processId }
            }
            if (reachedServer) refreshLibrary()
        }
    }

    /** Read from the journal, so it already counts the job [drain] finished last. */
    suspend fun unseenResult(): UploadResult? = owner()?.let { owner -> uploadResult(journal.jobs(owner).filterNot { it.hidden }) }

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
    ): Boolean = coroutineScope {
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
        val file = UploadedFile(claim.selection, DavFilePath(account.userId, receipt.path), receipt.created, receipt.fileId)
        for (step in FollowUp.entries) {
            if (claim.steps[step] != StepState.Pending) continue
            val state = attempt { followUp(step, account, claim, file, !acceptedNow) } ?: return journal.defer(claim).let { defer(); true }
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
        if (!sourceUnchanged(claim.selection)) return journal.problem(claim, UploadProblem.Source).let { null }
        val uploaded = try {
            put(account, claim) { percent -> progress.value = Progress(claim.id, percent) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (error.temporary) return journal.defer(claim).let { defer(); null }
            val failure = error as? UploadException
            return journal.problem(claim, failure?.problem ?: UploadProblem.Unexpected, httpStatus = failure?.httpStatus).let { null }
        }
        if (uploaded == null) return journal.problem(claim, null, conflict = true).let { null }
        // The file may change while it is read; then the server copy is not this phone file.
        if (!sourceUnchanged(claim.selection)) return journal.problem(claim, UploadProblem.Source).let { null }
        return journal.accept(claim, UploadReceipt(uploaded.path.segments, uploaded.created, uploaded.fileId))
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

private data class Progress(val id: String, val percent: Int?)

/** Done or Attention; null when the step failed temporarily and should run again later. */
private suspend fun attempt(action: suspend () -> Boolean): StepState? =
    try {
        if (action()) StepState.Done else StepState.Attention
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (error.temporary) null else StepState.Attention
    }

/**
 * No connection, a timeout, a server that is busy or down, or a lock (423) still held by an
 * interrupted request; another attempt may succeed.
 */
private val Exception.temporary: Boolean
    get() = when (this) {
        is UploadException -> httpStatus?.let(::temporaryStatus) == true
        is DavHttpException -> temporaryStatus(code)
        is IOException -> true
        else -> false
    }

private fun temporaryStatus(code: Int) = code == 408 || code == 423 || code == 429 || code >= 500

private fun uploadEntries(rows: List<UploadJob>, progress: Progress?): List<UploadEntry> =
    rows.map { row ->
        val phase = when (row.state) {
            TransferState.Pending, TransferState.Uncertain -> UploadPhase.Waiting
            TransferState.Running -> UploadPhase.Uploading
            TransferState.Accepted -> if (row.receipt?.created == true) UploadPhase.Uploaded else UploadPhase.Existing
            TransferState.Conflict -> UploadPhase.NameConflict
            TransferState.Attention -> UploadPhase.NeedsAttention
            TransferState.Cancelled -> UploadPhase.Cancelled
        }
        UploadEntry(
            id = row.id,
            selection = row.selection,
            phase = phase,
            percent = progress?.takeIf { it.id == row.id && phase == UploadPhase.Uploading }?.percent,
            problem = row.takeIf { it.hasAttention }?.problemText(),
            cancellable = row.unfinished,
            retryable = row.receipt != null && row.hasAttention && row.unfinished,
        )
    }

private fun active(job: UploadJob) = job.runnable || job.executor != null

private fun uploadActivity(rows: List<UploadJob>, pass: Set<String>, progress: Progress?): UploadActivity? {
    val active = rows.filter(::active)
    if (active.isEmpty()) return null
    val current = progress?.let { p -> active.firstOrNull { it.id == p.id && it.state == TransferState.Running } }
    return UploadActivity(
        pending = active.mapTo(HashSet()) { it.selection.identityUri },
        total = pass.size,
        processed = pass.size - active.size,
        current = current?.selection?.identityUri,
        percent = progress?.percent?.takeIf { current != null },
    )
}

private fun uploadResult(rows: List<UploadJob>): UploadResult? {
    val shown = rows.filter { !it.seen && it.hasResult }
    if (shown.isEmpty()) return null
    return UploadResult(
        files = shown.size,
        uploaded = shown.count { it.receipt != null },
        existing = shown.count { it.receipt?.created == false },
        cancelled = shown.count { it.state == TransferState.Cancelled },
        conflicts = shown.count { it.state == TransferState.Conflict },
        problems = shown.filter { it.hasAttention }.map { it.problemText() }.distinct(),
    )
}

private fun UploadJob.problemText(): UiText {
    val name = selection.displayName
    if (state != TransferState.Attention) {
        when (steps.entries.firstOrNull { it.value == StepState.Attention }?.key) {
            FollowUp.Link -> return uiText(R.string.upload_error_link)
            FollowUp.Album -> return uiText(R.string.upload_error_album)
            null -> Unit
        }
    }
    return when (problem ?: UploadProblem.Unexpected) {
        UploadProblem.Source -> uiText(R.string.upload_error_source, name)
        UploadProblem.Timeline -> uiText(R.string.upload_error_timeline)
        UploadProblem.Directory -> uiText(R.string.upload_error_directory, httpStatus ?: 0, name)
        UploadProblem.Http -> uiText(R.string.upload_error_http, httpStatus ?: 0, name)
        UploadProblem.Memories -> uiText(R.string.upload_error_memories, httpStatus ?: 0)
        UploadProblem.Unexpected -> uiText(R.string.upload_error_unexpected)
    }
}

/** Title and lines shared by the status pill and the result notification. */
internal fun UploadResult.title(): UiText =
    if (failed) uiText(R.string.upload_count, uploaded, files) else uiText(R.string.upload_done, uploaded, files)

internal fun UploadResult.details(): List<UiText> =
    buildList {
        addAll(problems)
        if (existing > 0) add(uiText(R.string.upload_existing_count, existing))
        if (cancelled > 0) add(uiText(R.string.upload_cancelled_count, cancelled))
        if (conflicts > 0) add(pluralText(R.plurals.upload_name_taken, conflicts, conflicts))
    }

private fun uploadOperationSnapshots(activity: UploadActivity?, result: UploadResult?): List<OperationStatusSnapshot> =
    buildList {
        if (activity != null) {
            val progress = if (activity.total == 0) null else (activity.processed + (activity.percent ?: 0) / 100f) / activity.total
            val media = buildList {
                activity.current?.let { add(OperationMediaActivity(OperationMediaReference.LocalUri(it), activity.percent?.div(100f))) }
                activity.pending.asSequence().filterNot { it == activity.current }.mapTo(this) {
                    OperationMediaActivity(OperationMediaReference.LocalUri(it), 0f)
                }
            }
            add(
                OperationStatusSnapshot(
                    kind = OperationKind.Upload,
                    title = uiText(R.string.upload_progress, (activity.processed + 1).coerceAtMost(activity.total), activity.total),
                    running = true,
                    progress = progress,
                    media = media,
                ),
            )
        }
        if (result != null) {
            add(
                OperationStatusSnapshot(
                    kind = OperationKind.Upload,
                    title = result.title(),
                    details = result.details(),
                    failed = result.failed,
                ),
            )
        }
    }
