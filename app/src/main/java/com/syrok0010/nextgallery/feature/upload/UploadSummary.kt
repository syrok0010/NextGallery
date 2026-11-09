package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.operation.OperationKind
import com.syrok0010.nextgallery.core.operation.OperationMediaActivity
import com.syrok0010.nextgallery.core.operation.OperationMediaReference
import com.syrok0010.nextgallery.core.operation.OperationStatusSnapshot
import com.syrok0010.nextgallery.core.ui.UiText
import com.syrok0010.nextgallery.core.ui.pluralText
import com.syrok0010.nextgallery.core.ui.uiText

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

internal fun uploadActivity(rows: List<UploadJob>, pass: Set<String>, progress: Progress?): UploadActivity? {
    val active = rows.filter { it.active }
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

internal fun uploadResult(rows: List<UploadJob>): UploadResult? {
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

internal fun uploadOperationSnapshots(activity: UploadActivity?, result: UploadResult?): List<OperationStatusSnapshot> =
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
