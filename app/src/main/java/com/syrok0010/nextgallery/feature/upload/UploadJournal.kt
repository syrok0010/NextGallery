package com.syrok0010.nextgallery.feature.upload

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import com.syrok0010.nextgallery.core.session.AccountCredentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.Json

/** Opaque login token plus the actual account address; never app passwords. */
internal val AccountCredentials.uploadOwner: String
    get() = uploadJson.encodeToString(QueueOwner(ownerToken, serverUrl.toHttpUrl().toString().trimEnd('/'), userId))

@Serializable
private data class QueueOwner(val token: String, val server: String, val uid: String, val version: Int = 1)

@Serializable
internal data class UploadIntent(val kind: String, val version: Int = 1, val owner: String = "", val target: String = "", val albumId: Long? = null, val albumHref: String? = null)

@Serializable
internal data class SourceStamp(val size: Long, val modified: Long?, val generation: Long?)

@Serializable
internal data class UploadReceipt(val url: String, val created: Boolean, val fileId: Long?, val version: Int = 1)

internal enum class TransferState { Pending, Running, Uncertain, Accepted, Conflict, Attention, Cancelled }
internal enum class StepState { None, Pending, Running, Done, Attention, Uncertain, Suppressed }

/** A step result never replaces the transfer receipt. Unknown remote effects remain uncertain. */
@Serializable
internal data class UploadStepResult(
    val state: String,
    val problem: String? = null,
    val fileId: Long? = null,
    val targetId: Long? = null,
    val href: String? = null,
    val version: Int = 1,
) {
    companion object {
        fun attention(problem: String) = UploadStepResult("Attention", problem)
        fun uncertain() = UploadStepResult("Uncertain", "Uncertain")
    }
}
internal enum class UploadProblem { Source, Transfer, Foreground, Link, Album, Format, Directory, Http }

@Entity(tableName = "upload_owner")
internal data class UploadOwner(@PrimaryKey val slot: Int = 1, val token: String?)

@Entity(tableName = "upload_jobs", indices = [Index(value = ["owner", "requestId", "position"], unique = true)])
internal data class UploadJob(
    @PrimaryKey val id: String,
    val batchId: String,
    val owner: String,
    val requestId: String,
    val position: Int,
    val selectionJson: String,
    val intentsJson: String,
    val state: String = TransferState.Pending.name,
    val destination: String? = null,
    val receiptJson: String? = null,
    val linkStep: String = StepState.None.name,
    val albumStep: String = StepState.None.name,
    val problem: String? = null,
    val httpStatus: Int? = null,
    val passId: String = "",
    val revision: Long = 0,
    val executor: String? = null,
    val attempts: Int = 0,
    // Scheduler/user preferences are deliberately not chosen in this slice.
    val policyVersion: Int = 1,
    val hidden: Boolean = false,
    val seen: Boolean = false,
    val linkResultJson: String? = null,
    val albumResultJson: String? = null,
) {
    val selection get() = uploadJson.decodeFromString<UploadSelection>(selectionJson)
    val intents get() = uploadJson.decodeFromString<List<UploadIntent>>(intentsJson)
    val receipt get() = receiptJson?.let { uploadJson.decodeFromString<UploadReceipt>(it) }
    val unfinished get() = state in setOf("Pending", "Running", "Uncertain", "Attention") ||
        linkStep in unfinishedSteps || albumStep in unfinishedSteps
    val runnable get() = state in setOf("Pending", "Uncertain") ||
        (receiptJson != null && (linkStep == "Pending" || albumStep == "Pending"))
    val hasAttention get() = state == "Attention" || linkStep in setOf("Attention", "Uncertain") || albumStep in setOf("Attention", "Uncertain")
    val hasResult get() = !unfinished || hasAttention
}

private val unfinishedSteps = setOf("Pending", "Running", "Attention", "Uncertain")
internal val uploadJson = Json { ignoreUnknownKeys = false; encodeDefaults = true }

@Dao
internal interface UploadJournalDao {
    @Query("SELECT * FROM upload_owner WHERE slot = 1") suspend fun owner(): UploadOwner?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun owner(owner: UploadOwner)
    @Query("SELECT * FROM upload_jobs WHERE owner = :owner ORDER BY rowid") suspend fun jobs(owner: String): List<UploadJob>
    @Query("SELECT * FROM upload_jobs WHERE owner = :owner AND hidden = 0 ORDER BY rowid") fun observe(owner: String): Flow<List<UploadJob>>
    @Query("SELECT * FROM upload_jobs WHERE id = :id") suspend fun job(id: String): UploadJob?
    @Insert suspend fun insert(jobs: List<UploadJob>)
    @Update suspend fun update(job: UploadJob)
    @Query("DELETE FROM upload_jobs") suspend fun clear()
}

@Database(entities = [UploadJob::class, UploadOwner::class], version = 2, exportSchema = true)
internal abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun journal(): UploadJournalDao
    companion object {
        fun open(context: Context, name: String = "upload-journal.db"): UploadQueueDatabase =
            Room.databaseBuilder(context, UploadQueueDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE upload_jobs ADD COLUMN linkResultJson TEXT")
                db.execSQL("ALTER TABLE upload_jobs ADD COLUMN albumResultJson TEXT")
            }
        }
    }
}

internal sealed interface EnqueueResult {
    data class Accepted(val batchId: String, val count: Int) : EnqueueResult
    data object Rejected : EnqueueResult
    data object NothingToUpload : EnqueueResult
}

/** Durable protocol. Every mutation and its fencing check share one Room writer transaction. */
internal class UploadJournal(private val db: UploadQueueDatabase) {
    private val dao = db.journal()
    fun observe(owner: String) = dao.observe(owner)
    suspend fun jobs(owner: String) = dao.jobs(owner)

    suspend fun activate(owner: String?) = db.withTransaction {
        // Missing credentials are not an explicit logout and must not erase accepted intents.
        if (owner == null) return@withTransaction
        if (dao.owner()?.token != owner) {
            // The journal is account scoped, credentials live only in the secure session store.
            dao.clear()
            dao.owner(UploadOwner(token = owner))
        }
    }

    suspend fun recover(owner: String, process: String) = db.withTransaction {
        if (dao.owner()?.token != owner) return@withTransaction
        dao.jobs(owner).filter { it.executor != null && it.executor != process }.forEach { old ->
            dao.update(old.copy(
                state = if (old.state == "Running") "Uncertain" else old.state,
                linkStep = if (old.linkStep == "Running") "Pending" else old.linkStep,
                albumStep = if (old.albumStep in setOf("Pending", "Running")) {
                    if (replayableAlbum(old)) "Pending" else "Attention"
                } else old.albumStep,
                albumResultJson = if (old.albumStep in setOf("Pending", "Running") && !replayableAlbum(old))
                    uploadJson.encodeToString(UploadStepResult.attention("LegacyTarget")) else old.albumResultJson,
                seen = if (old.albumStep in setOf("Pending", "Running") && !replayableAlbum(old)) false else old.seen,
                executor = null,
                revision = old.revision + 1,
            ))
        }
    }

    private fun replayableAlbum(job: UploadJob): Boolean = try {
        job.intents.singleOrNull { it.kind == "album" }?.let {
            it.version == 2 && it.albumId != null && it.albumHref != null
        } == true
    } catch (_: Exception) { false }

    suspend fun enqueue(owner: String, requestId: String, files: List<UploadSelection>, intents: List<UploadIntent>): EnqueueResult =
        try {
            db.withTransaction {
                if (dao.owner()?.token != owner || files.isEmpty()) return@withTransaction EnqueueResult.Rejected
                val prior = dao.jobs(owner).filter { it.requestId == requestId }
                if (prior.isNotEmpty()) return@withTransaction EnqueueResult.Accepted(prior.first().batchId, prior.size)
                val batch = UUID.randomUUID().toString()
                val pass = dao.jobs(owner).lastOrNull { it.runnable || it.executor != null }?.passId ?: UUID.randomUUID().toString()
                dao.insert(files.mapIndexed { index, file ->
                    UploadJob(UUID.randomUUID().toString(), batch, owner, requestId, index,
                        uploadJson.encodeToString(file), uploadJson.encodeToString(intents), passId = pass)
                })
                EnqueueResult.Accepted(batch, files.size)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EnqueueResult.Rejected
        }

    suspend fun prior(owner: String, requestId: String): EnqueueResult.Accepted? =
        dao.jobs(owner).filter { it.requestId == requestId }.takeIf { it.isNotEmpty() }?.let {
            EnqueueResult.Accepted(it.first().batchId, it.size)
        }

    suspend fun claim(id: String, owner: String, process: String): UploadJob? = db.withTransaction {
        val old = dao.job(id) ?: return@withTransaction null
        if (dao.owner()?.token != owner || old.owner != owner || !old.runnable || old.executor != null) return@withTransaction null
        old.copy(state = if (old.receiptJson == null) "Running" else old.state,
            executor = process, revision = old.revision + 1, attempts = old.attempts + 1).also { dao.update(it) }
    }

    private suspend fun mutate(claim: UploadJob, change: (UploadJob) -> UploadJob?): UploadJob? = db.withTransaction {
        val old = dao.job(claim.id) ?: return@withTransaction null
        if (dao.owner()?.token != claim.owner || old.owner != claim.owner ||
            old.executor != claim.executor || old.revision != claim.revision || old.state == "Cancelled") return@withTransaction null
        change(old)?.also { dao.update(it) }
    }

    suspend fun destination(claim: UploadJob, url: String): UploadJob? =
        mutate(claim) { it.copy(destination = it.destination ?: url) }

    suspend fun accept(claim: UploadJob, receipt: UploadReceipt): UploadJob? = mutate(claim) {
        if (it.receiptJson != null || it.state != "Running") null else it.copy(state = "Accepted", receiptJson = uploadJson.encodeToString(receipt),
            linkStep = "Pending", albumStep = if (it.intents.any { intent -> intent.kind == "album" }) "Pending" else "None")
    }

    suspend fun problem(claim: UploadJob, problem: UploadProblem, conflict: Boolean = false, httpStatus: Int? = null) =
        mutate(claim) { it.copy(state = if (conflict) "Conflict" else "Attention", problem = problem.name, httpStatus = httpStatus, executor = null, seen = false) }

    suspend fun step(claim: UploadJob, kind: String, state: StepState, problem: String? = null): UploadJob? = mutate(claim) {
        val old = if (kind == "link") it.linkStep else it.albumStep
        if (old !in setOf("Pending", "Running")) null
        else if (kind == "link") it.copy(linkStep = state.name, linkResultJson = problem?.let { reason -> uploadJson.encodeToString(UploadStepResult.attention(reason)) } ?: it.linkResultJson, seen = false)
        else it.copy(albumStep = state.name, albumResultJson = problem?.let { reason -> uploadJson.encodeToString(UploadStepResult.attention(reason)) } ?: it.albumResultJson, seen = false)
    }
    suspend fun beginStep(claim: UploadJob, kind: String): UploadJob? = mutate(claim) {
        val state = if (kind == "link") it.linkStep else it.albumStep
        if (state != "Pending" || it.receiptJson == null) null
        else if (kind == "link") it.copy(linkStep = "Running", revision = it.revision + 1)
        else it.copy(albumStep = "Running", revision = it.revision + 1)
    }

    suspend fun stepCurrent(claim: UploadJob, kind: String): Boolean = db.withTransaction {
        val old = dao.job(claim.id)
        dao.owner()?.token == claim.owner && old?.owner == claim.owner &&
            old.executor == claim.executor && old.revision == claim.revision &&
            (if (kind == "link") old.linkStep else old.albumStep) == "Running"
    }

    suspend fun finishStep(claim: UploadJob, kind: String, result: UploadStepResult): UploadJob? = mutate(claim) {
        val state = if (kind == "link") it.linkStep else it.albumStep
        if (state != "Running" || result.state !in setOf("Done", "Attention", "Uncertain")) null
        else if (kind == "link") it.copy(linkStep = result.state, linkResultJson = uploadJson.encodeToString(result), revision = it.revision + 1, seen = false)
        else it.copy(albumStep = result.state, albumResultJson = uploadJson.encodeToString(result), revision = it.revision + 1, seen = false)
    }

    /** Explicit retry changes only unresolved steps; it can never make a receipt runnable as PUT. */
    suspend fun retrySteps(owner: String, id: String) = db.withTransaction {
        val old = dao.job(id) ?: return@withTransaction
        if (dao.owner()?.token != owner || old.owner != owner || old.receiptJson == null || old.executor != null) return@withTransaction
        dao.update(old.copy(
            linkStep = if (old.linkStep in setOf("Attention", "Uncertain")) "Pending" else old.linkStep,
            albumStep = if (old.albumStep in setOf("Attention", "Uncertain")) "Pending" else old.albumStep,
            revision = old.revision + 1,
        ))
    }

    suspend fun release(claim: UploadJob) = mutate(claim) { it.copy(executor = null) }

    suspend fun interrupt(owner: String, batches: Set<String>, process: String) = db.withTransaction {
        if (dao.owner()?.token != owner) return@withTransaction
        dao.jobs(owner).filter {
            it.batchId in batches && (it.executor == null || it.executor == process) &&
                (it.executor == process || (it.unfinished && (it.runnable || it.state == "Running" || it.linkStep == "Running" || it.albumStep == "Running")))
        }.forEach {
            // Stop may arrive after the last step commit and before the caller releases its
            // claim. Release that claim without turning the accepted result into an error.
            if (!it.unfinished) {
                dao.update(it.copy(executor = null, revision = it.revision + 1))
                return@forEach
            }
            dao.update(it.copy(state = if (it.receiptJson == null) "Attention" else it.state,
                linkStep = if (it.linkStep in setOf("Pending", "Running")) "Attention" else it.linkStep,
                albumStep = if (it.albumStep == "Running") "Uncertain" else if (it.albumStep == "Pending") "Attention" else it.albumStep,
                albumResultJson = if (it.albumStep == "Running") uploadJson.encodeToString(UploadStepResult.uncertain()) else it.albumResultJson,
                problem = UploadProblem.Transfer.name, executor = null, revision = it.revision + 1, seen = false))
        }
    }

    suspend fun cancel(owner: String, ids: Set<String>? = null) = db.withTransaction {
        if (dao.owner()?.token != owner) return@withTransaction
        dao.jobs(owner).filter { (ids == null || it.id in ids) && it.unfinished }.forEach {
            dao.update(it.copy(state = if (it.receiptJson == null) "Cancelled" else it.state,
                linkStep = if (it.linkStep in unfinishedSteps) "Suppressed" else it.linkStep,
                albumStep = if (it.albumStep in unfinishedSteps) "Suppressed" else it.albumStep,
                executor = null, revision = it.revision + 1, seen = false))
        }
    }
    suspend fun clearFinished(owner: String) = db.withTransaction {
        dao.jobs(owner).groupBy { it.batchId }.values.filter { batch -> batch.none { it.unfinished } }.flatten().forEach { dao.update(it.copy(hidden = true)) }
    }
    suspend fun seen(owner: String) = db.withTransaction {
        dao.jobs(owner).filter { it.hasResult && !it.seen }.forEach { dao.update(it.copy(seen = true)) }
    }
    suspend fun revokeIfFinished(owner: String): Boolean = db.withTransaction {
        if (dao.owner()?.token != owner || dao.jobs(owner).any { it.unfinished }) return@withTransaction false
        dao.clear()
        dao.owner(UploadOwner(token = null))
        true
    }

    suspend fun canLogout(owner: String): Boolean = db.withTransaction {
        dao.owner()?.token == owner && dao.jobs(owner).none { it.unfinished }
    }
}
