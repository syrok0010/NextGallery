package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind

/**
 * Keeps an uploaded phone file and its new server file one media object. Memories 10.1 does not
 * return AUID/BUID, so the server copy cannot be matched later; the upload is the only moment
 * both identities are known together.
 */
class UploadedCopies(
    private val identities: MediaIdentityRegistry,
    private val refreshLibrary: () -> Unit,
) {
    suspend fun link(contentUri: String, fileId: Long): Boolean =
        identities.link(
            existing = MediaSourceIdentity(MediaSourceKind.Local, contentUri),
            linked = MediaSourceIdentity(MediaSourceKind.Memories, fileId.toString()),
        )

    /** Shows the server copies without waiting for a manual refresh. Main thread only. */
    fun uploaded() = refreshLibrary()
}

/**
 * File id from the `OC-FileId` response header: the id zero-padded to eight digits, followed by
 * the instance id, which always starts with "oc".
 */
internal fun uploadedFileId(header: String?): Long? =
    header
        ?.takeWhile(Char::isDigit)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
