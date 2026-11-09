package com.syrok0010.nextgallery.feature.upload

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.serialization.Serializable

/** Jobs belong to an account. Logout needs an empty queue and clears the journal. */
internal val AccountCredentials.uploadOwner: String get() = "${serverUrl.trimEnd('/')}#$userId"

/** Album the user picked; the file is added to it once uploaded. */
@Serializable
internal data class UploadAlbum(val owner: String, val name: String)

/** The file is on the server at [path] (`DavFilePath.segments`): uploaded now ([created]) or found with the same bytes. */
@Serializable
internal data class UploadReceipt(val path: List<String>, val created: Boolean, val fileId: Long?)

internal enum class TransferState { Pending, Running, Uncertain, Accepted, Conflict, Attention, Cancelled }

/**
 * One file of a manual upload, a row of the journal. [executor] is the process running it,
 * [revision] fences writes of a claim that was taken away meanwhile. [steps] run only after the
 * [receipt].
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

    /** Runnable and nobody runs it yet. */
    val waiting: Boolean get() = runnable && executor == null

    /** Waits for a worker or is being run by one. */
    val active: Boolean get() = runnable || executor != null
    val hasAttention: Boolean
        get() = state == TransferState.Attention || StepState.Attention in steps.values
    val hasResult: Boolean get() = !unfinished || hasAttention

    /**
     * Nobody runs the job any more. A transfer that was on the wire has an unknown outcome and runs
     * again; a step stays pending until it is done, so a later attempt repeats it.
     */
    fun released() = copy(
        state = if (state == TransferState.Running) TransferState.Uncertain else state,
        executor = null,
        revision = revision + 1,
    )
}

private val unfinishedStates = setOf(TransferState.Pending, TransferState.Running, TransferState.Uncertain, TransferState.Attention)
internal val unfinishedSteps = setOf(StepState.Pending, StepState.Attention)
