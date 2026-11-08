package com.syrok0010.nextgallery.feature.upload

import android.content.Context
import androidx.room.*
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Jobs belong to an account. Logout needs an empty queue and clears the journal. */
internal val AccountCredentials.uploadOwner: String get() = "${serverUrl.trimEnd('/')}#$userId"

/** Metadata of the phone file when it was queued; a different value later means it changed. */
@Serializable
internal data class SourceStamp(val size: Long, val modified: Long?, val generation: Long?)

/** Album the user picked; the file is added to it once uploaded. */
@Serializable
internal data class UploadAlbum(val owner: String, val name: String)

/** The file is on the server at [path] (`DavFilePath.segments`): uploaded now ([created]) or found with the same bytes. */
@Serializable
internal data class UploadReceipt(val path: List<String>, val created: Boolean, val fileId: Long?)

internal enum class TransferState { Pending, Running, Uncertain, Accepted, Conflict, Attention, Cancelled }

/**
 * Work after the [UploadJob.receipt], run in this order. The journal only tracks each step's
 * state; the queue gets one function that knows what a step does.
 */
internal enum class FollowUp { Link, Album }

internal enum class StepState { Pending, Done, Attention, Suppressed }

/** Why a transfer needs the user; shown on the uploads screen and in the result. */
internal enum class UploadProblem { Source, Timeline, Directory, Http, Memories, Unexpected }

/**
 * One file of a manual upload. [executor] is the process running it, [revision] fences writes of
 * a claim that was taken away meanwhile. [steps] run only after the [receipt].
 */
@Entity(tableName = "upload_jobs")
internal data class UploadJob(
    @PrimaryKey val id: String,
    val owner: String,
    val selection: UploadSelection,
    val album: UploadAlbum? = null,
    val state: TransferState = TransferState.Pending,
    /** Pinned original name, so a restart looks for an earlier copy under the same names. */
    val destination: List<String>? = null,
    val receipt: UploadReceipt? = null,
    val steps: Map<FollowUp, StepState> = emptyMap(),
    val problem: UploadProblem? = null,
    val httpStatus: Int? = null,
    val executor: String? = null,
    val revision: Long = 0,
    val hidden: Boolean = false,
    val seen: Boolean = false,
) {
    val unfinished: Boolean
        get() = state in unfinishedStates || steps.values.any { it in unfinishedSteps }
    val runnable: Boolean
        get() = state == TransferState.Pending || state == TransferState.Uncertain ||
            (receipt != null && StepState.Pending in steps.values)
    val hasAttention: Boolean
        get() = state == TransferState.Attention || StepState.Attention in steps.values
    val hasResult: Boolean get() = !unfinished || hasAttention
}

private val unfinishedStates = setOf(TransferState.Pending, TransferState.Running, TransferState.Uncertain, TransferState.Attention)
private val unfinishedSteps = setOf(StepState.Pending, StepState.Attention)
private val uploadJson = Json

internal class UploadConverters {
    @TypeConverter fun fromSelection(value: UploadSelection): String = uploadJson.encodeToString(value)
    @TypeConverter fun toSelection(value: String): UploadSelection = uploadJson.decodeFromString(value)
    @TypeConverter fun fromAlbum(value: UploadAlbum?): String? = value?.let(uploadJson::encodeToString)
    @TypeConverter fun toAlbum(value: String?): UploadAlbum? = value?.let(uploadJson::decodeFromString)
    @TypeConverter fun fromReceipt(value: UploadReceipt?): String? = value?.let(uploadJson::encodeToString)
    @TypeConverter fun toReceipt(value: String?): UploadReceipt? = value?.let(uploadJson::decodeFromString)
    @TypeConverter fun fromPath(value: List<String>?): String? = value?.let(uploadJson::encodeToString)
    @TypeConverter fun toPath(value: String?): List<String>? = value?.let(uploadJson::decodeFromString)
    @TypeConverter fun fromSteps(value: Map<FollowUp, StepState>): String = uploadJson.encodeToString(value)
    @TypeConverter fun toSteps(value: String): Map<FollowUp, StepState> = uploadJson.decodeFromString(value)
}

@Dao
internal interface UploadJournalDao {
    @Query("SELECT * FROM upload_jobs WHERE owner = :owner ORDER BY rowid") suspend fun jobs(owner: String): List<UploadJob>
    @Query("SELECT * FROM upload_jobs WHERE owner = :owner AND hidden = 0 ORDER BY rowid") fun observe(owner: String): Flow<List<UploadJob>>
    @Query("SELECT * FROM upload_jobs WHERE id = :id") suspend fun job(id: String): UploadJob?
    @Insert suspend fun insert(jobs: List<UploadJob>)
    @Update suspend fun update(jobs: List<UploadJob>)
    @Query("DELETE FROM upload_jobs") suspend fun clear()
}

@Database(entities = [UploadJob::class], version = 1, exportSchema = false)
@TypeConverters(UploadConverters::class)
internal abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun journal(): UploadJournalDao

    companion object {
        /** Apart from the cache DB, which is rebuilt freely; a schema change still resets it, the app is not released yet. */
        fun open(context: Context, name: String = "upload-journal.db"): UploadQueueDatabase =
            Room.databaseBuilder(context, UploadQueueDatabase::class.java, name)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}

internal sealed interface EnqueueResult {
    data class Accepted(val count: Int) : EnqueueResult
    data object Rejected : EnqueueResult
    data object NothingToUpload : EnqueueResult
}

/** Every change and its fencing check share one Room transaction. */
internal class UploadJournal(private val db: UploadQueueDatabase) {
    private val dao = db.journal()

    fun observe(owner: String) = dao.observe(owner)

    suspend fun jobs(owner: String) = dao.jobs(owner)

    suspend fun enqueue(owner: String, files: List<UploadSelection>, album: UploadAlbum?): EnqueueResult =
        try {
            db.withTransaction {
                dao.insert(files.map { UploadJob(UUID.randomUUID().toString(), owner, it, album) })
                EnqueueResult.Accepted(files.size)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EnqueueResult.Rejected
        }

    /** Takes the first runnable job nobody runs, except those in [skip]. */
    suspend fun claimNext(owner: String, executor: String, skip: Set<String> = emptySet()): UploadJob? = db.withTransaction {
        val job = dao.jobs(owner).firstOrNull { it.runnable && it.executor == null && it.id !in skip } ?: return@withTransaction null
        job.copy(
            state = if (job.receipt == null) TransferState.Running else job.state,
            executor = executor,
            revision = job.revision + 1,
        ).also { update(it) }
    }

    /** Gives up the claims of executors that match [executor]. */
    suspend fun release(owner: String, executor: (String) -> Boolean) = db.withTransaction {
        dao.update(dao.jobs(owner).filter { it.executor?.let(executor) == true }.map(UploadJob::released))
    }

    /** Gives a job back after a temporary failure; it runs again on a later attempt. */
    suspend fun defer(claim: UploadJob) = mutate(claim, UploadJob::released)

    /** Applies [change] only while [claim] still holds the job and the user has not cancelled it. */
    private suspend fun mutate(claim: UploadJob, change: (UploadJob) -> UploadJob): UploadJob? = db.withTransaction {
        val old = dao.job(claim.id) ?: return@withTransaction null
        if (old.executor != claim.executor || old.revision != claim.revision || old.state == TransferState.Cancelled) {
            return@withTransaction null
        }
        change(old).also { update(it) }
    }

    suspend fun pin(claim: UploadJob, path: List<String>): UploadJob? = mutate(claim) { it.copy(destination = it.destination ?: path) }

    /** Every file links its phone copy; one with an album is added to it as well. */
    suspend fun accept(claim: UploadJob, receipt: UploadReceipt): UploadJob? = mutate(claim) { job ->
        val steps = listOfNotNull(FollowUp.Link, FollowUp.Album.takeIf { job.album != null })
        job.copy(state = TransferState.Accepted, receipt = receipt, steps = steps.associateWith { StepState.Pending })
    }

    suspend fun problem(claim: UploadJob, problem: UploadProblem?, conflict: Boolean = false, httpStatus: Int? = null) = mutate(claim) {
        it.copy(
            state = if (conflict) TransferState.Conflict else TransferState.Attention,
            problem = problem,
            httpStatus = httpStatus,
            executor = null,
            seen = false,
        )
    }

    suspend fun step(claim: UploadJob, step: FollowUp, state: StepState) = mutate(claim) {
        it.copy(steps = it.steps + (step to state), seen = false)
    }

    /** Retries only actions after a durable receipt; the transfer and its destination stay intact. */
    suspend fun retry(owner: String, ids: Set<String>? = null) = db.withTransaction {
        dao.update(
            dao.jobs(owner).filter {
                (ids == null || it.id in ids) && it.receipt != null && it.hasAttention && it.unfinished
            }.map { job ->
                job.copy(
                    steps = job.steps.mapValues { (_, state) -> if (state == StepState.Attention) StepState.Pending else state },
                    executor = null,
                    revision = job.revision + 1,
                    seen = false,
                )
            },
        )
    }

    suspend fun finish(claim: UploadJob) = mutate(claim) { it.copy(executor = null) }

    /** A transfer not accepted yet is cancelled; after the receipt only the remaining steps are. */
    suspend fun cancel(owner: String, ids: Set<String>? = null) = db.withTransaction {
        dao.update(dao.jobs(owner).filter { (ids == null || it.id in ids) && it.unfinished }.map {
            it.copy(
                state = if (it.receipt == null) TransferState.Cancelled else it.state,
                steps = it.steps.mapValues { (_, state) -> if (state in unfinishedSteps) StepState.Suppressed else state },
                executor = null,
                revision = it.revision + 1,
                seen = false,
            )
        })
    }

    /** Per file: a finished file leaves the screen even while others still run. */
    suspend fun clearFinished(owner: String) = db.withTransaction {
        dao.update(dao.jobs(owner).filter { !it.unfinished && !it.hidden }.map { it.copy(hidden = true) })
    }

    suspend fun seen(owner: String) = db.withTransaction {
        dao.update(dao.jobs(owner).filter { it.hasResult && !it.seen }.map { it.copy(seen = true) })
    }

    /** Clears the journal for logout unless some work is unfinished. */
    suspend fun clearIfFinished(owner: String): Boolean = db.withTransaction {
        if (dao.jobs(owner).any { it.unfinished }) return@withTransaction false
        dao.clear()
        true
    }

    private suspend fun update(job: UploadJob) = dao.update(listOf(job))
}

/**
 * A job nobody runs any more. A transfer that was on the wire has an unknown outcome and runs
 * again; a step stays pending until it is done, so a later attempt repeats it.
 */
private fun UploadJob.released() = copy(
    state = if (state == TransferState.Running) TransferState.Uncertain else state,
    executor = null,
    revision = revision + 1,
)
