package com.syrok0010.nextgallery.feature.upload

import androidx.room.withTransaction
import java.util.UUID

internal sealed interface EnqueueResult {
    data class Accepted(val count: Int) : EnqueueResult
    data object Rejected : EnqueueResult
    data object NothingToUpload : EnqueueResult
}

/** Every change and its fencing check share one Room transaction. */
internal class UploadJournal(private val db: UploadQueueDatabase) {
    private val dao = db.journal()

    fun observe() = dao.observe()

    suspend fun jobs() = dao.jobs()

    suspend fun enqueue(files: List<UploadSelection>, album: UploadAlbum?): EnqueueResult =
        try {
            db.withTransaction {
                dao.insert(files.map { UploadJob(UUID.randomUUID().toString(), it, album) })
                EnqueueResult.Accepted(files.size)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EnqueueResult.Rejected
        }

    /** Takes the first runnable job nobody runs, except those in [skip]. */
    suspend fun claimNext(executor: String, skip: Set<String> = emptySet()): UploadJob? = db.withTransaction {
        val job = dao.jobs().firstOrNull { it.waiting && it.id !in skip } ?: return@withTransaction null
        job.copy(
            state = if (job.receipt == null) TransferState.Running else job.state,
            executor = executor,
            revision = job.revision + 1,
        ).also { update(it) }
    }

    /** Gives up the claims of executors that match [executor]. */
    suspend fun release(executor: (String) -> Boolean) = db.withTransaction {
        dao.update(dao.jobs().filter { it.executor?.let(executor) == true }.map(UploadJob::released))
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
    suspend fun retry(ids: Set<String>? = null) = db.withTransaction {
        dao.update(
            dao.jobs().filter {
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
    suspend fun cancel(ids: Set<String>? = null) = db.withTransaction {
        dao.update(dao.jobs().filter { (ids == null || it.id in ids) && it.unfinished }.map {
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
    suspend fun clearFinished() = db.withTransaction {
        dao.update(dao.jobs().filter { !it.unfinished && !it.hidden }.map { it.copy(hidden = true) })
    }

    suspend fun seen() = db.withTransaction {
        dao.update(dao.jobs().filter { it.hasResult && !it.seen }.map { it.copy(seen = true) })
    }

    /** Logout waits for this work; it erases the journal with the rest of the app data. */
    suspend fun hasUnfinished(): Boolean = dao.jobs().any { it.unfinished }

    private suspend fun update(job: UploadJob) = dao.update(listOf(job))
}
