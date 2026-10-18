package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.ui.UiText
import okhttp3.HttpUrl

data class UploadSelection(val contentUri: String, val displayName: String, val mimeType: String?)

/** Objects that exist only on the phone; others already have a server copy or no local bytes. */
internal fun uploadSelections(items: List<MediaItem>): List<UploadSelection> =
    items.filterNot { it.hasRemoteCopy }.mapNotNull { item ->
        item.localCopy?.let { local ->
            UploadSelection(
                contentUri = local.contentUri,
                displayName = item.displayName,
                mimeType = item.mimeType,
            )
        }
    }

/**
 * Files the user sent to the cloud in one action, with what the caller wants done to each of them
 * once it is on the server. Upload itself only puts bytes on the server.
 */
internal class UploadBatch(
    val files: List<UploadSelection>,
    val followUps: List<UploadFollowUp> = emptyList(),
)

/**
 * [selection] is on the server at [url]: uploaded now ([created]) or already there with the same
 * size. A different file under the same name never reaches follow-ups. [fileId] is the server file
 * id, known only for a created file: an existing one may be another photo of the same size.
 */
internal data class UploadedFile(
    val selection: UploadSelection,
    val url: HttpUrl,
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
