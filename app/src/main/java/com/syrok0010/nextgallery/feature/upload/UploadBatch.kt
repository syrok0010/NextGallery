package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.core.media.localCopy
import java.util.UUID
import okhttp3.HttpUrl

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
 * [selection] is on the server at [url]: uploaded now ([created]) or already there with the same
 * size. A different file under the same name never reaches follow-ups. [fileId] is the server file
 * id, when returned by DAV. For an existing file, equal sizes are accepted as the same file.
 */
internal data class UploadedFile(
    val selection: UploadSelection,
    val url: HttpUrl,
    val created: Boolean,
    val fileId: Long? = null,
)
