package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.ui.UiText
import java.util.concurrent.atomic.AtomicLong

/** Phone copy of one object, fixed by [UploadQueue.submit] when the object is queued. */
internal data class UploadSelection(
    val local: MediaAssetRef.LocalContent,
    val displayName: String,
    val mimeType: String?,
) {
    val contentUri: String get() = local.contentUri
}

/** Only objects that exist just on the phone are uploaded; others already have a server copy. */
internal fun uploadSelection(item: MediaItem): UploadSelection? =
    item.localCopy?.takeUnless { item.hasRemoteCopy }?.let { local ->
        UploadSelection(
            local = local,
            displayName = item.displayName,
            mimeType = item.mimeType,
        )
    }

/**
 * Files the user sent to the cloud in one action, with what the caller wants done to each of them
 * once it is on the server. Upload itself only puts bytes on the server.
 */
internal class UploadBatch(
    val files: List<UploadSelection>,
    val followUps: List<UploadFollowUp> = emptyList(),
) {
    val id = nextBatchId.incrementAndGet()
    val items = files.map(::UploadItem)
    fun cancel() = items.forEach(UploadItem::cancel)

    companion object {
        private val nextBatchId = AtomicLong()
    }
}

/**
 * [selection] is on the server at [path]: uploaded now ([created]) or already there with the same
 * bytes. [path] may be a neighbour name when the original one holds a different file. [fileId] is
 * the server file id when DAV returned it.
 */
internal data class UploadedFile(
    val selection: UploadSelection,
    val path: DavFilePath,
    val created: Boolean,
    val fileId: Long? = null,
)

/**
 * Work that belongs to the caller of an upload, for example adding files to an album or removing
 * the phone copy. One instance serves one batch, so it may keep the batch's results.
 *
 * Both calls run in the upload service before it leaves the foreground, so the work is not cut
 * short by the process being stopped right after the last upload.
 */
internal interface UploadFollowUp {
    /** Runs right after [file] is on the server, one file at a time. */
    suspend fun uploaded(credentials: AccountCredentials, file: UploadedFile)

    /** Runs after the batch; [missed] never reached the server. Returns notification lines. */
    suspend fun finished(missed: List<UploadSelection>): List<UiText>
}
