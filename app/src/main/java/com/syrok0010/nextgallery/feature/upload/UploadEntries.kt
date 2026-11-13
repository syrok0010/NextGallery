package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.ui.UiText

internal enum class UploadPhase { Waiting, Uploading, Uploaded, Existing, NameConflict, NeedsAttention, Cancelled }

/** One file on the uploads screen. */
internal data class UploadEntry(
    val id: String,
    val selection: UploadSelection,
    val phase: UploadPhase,
    val percent: Int? = null,
    /** Every byte is sent and the server has not answered yet. */
    val saving: Boolean = false,
    /** Set while the file needs the user, also after upload when a follow-up failed. */
    val problem: UiText? = null,
    /** Unfinished work blocks logout, so the user can always cancel it. */
    val cancellable: Boolean = false,
    /** A receipt exists and a follow-up can be retried without another upload. */
    val retryable: Boolean = false,
)

internal fun uploadEntries(rows: List<UploadJob>, progress: Progress?): List<UploadEntry> =
    rows.map { row ->
        val phase = when (row.state) {
            TransferState.Pending, TransferState.Uncertain -> UploadPhase.Waiting
            TransferState.Running -> UploadPhase.Uploading
            TransferState.Accepted -> if (row.receipt?.created == true) UploadPhase.Uploaded else UploadPhase.Existing
            TransferState.Conflict -> UploadPhase.NameConflict
            TransferState.Attention -> UploadPhase.NeedsAttention
            TransferState.Cancelled -> UploadPhase.Cancelled
        }
        val current = progress?.takeIf { it.id == row.id && phase == UploadPhase.Uploading }
        UploadEntry(
            id = row.id,
            selection = row.selection,
            phase = phase,
            percent = current?.percent,
            saving = current?.saving == true,
            problem = row.takeIf { it.hasAttention }?.problemText(),
            cancellable = row.unfinished,
            retryable = row.receipt != null && row.hasAttention && row.unfinished,
        )
    }
