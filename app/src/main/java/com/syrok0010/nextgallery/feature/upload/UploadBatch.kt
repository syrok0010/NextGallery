package com.syrok0010.nextgallery.feature.upload

import androidx.annotation.StringRes
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import java.util.UUID

/** Phone copy of one object, fixed by [UploadQueue.submit] when the object is queued. */
@kotlinx.serialization.Serializable
internal data class UploadSelection(
    val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val identityUri: String = uploadedLocalIdentityUri(contentUri),
    val mediaId: String? = null,
    val stamp: SourceStamp? = null,
)

/** Only objects that exist just on the phone are uploaded; others already have a server copy. */
internal fun uploadSelection(item: MediaItem): UploadSelection? =
    item.localCopy?.takeUnless { item.hasRemoteCopy }?.let { local ->
        UploadSelection(
            contentUri = local.contentUri,
            displayName = item.displayName,
            mimeType = item.mimeType,
            mediaId = item.mediaId.value,
        )
    }

/**
 * Files the user sent to the cloud in one action, with what the caller wants done to each of them
 * once it is on the server. Upload itself only puts bytes on the server.
 */
internal class UploadBatch(
    val files: List<UploadSelection>,
    val id: String = UUID.randomUUID().toString(),
    val items: List<UploadItem> = files.map(::UploadItem),
)

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

/** A failure the user can act on; [reason] is a string resource formatted with [args]. */
internal class UploadException(@StringRes val reason: Int, vararg val args: Any) : Exception()
