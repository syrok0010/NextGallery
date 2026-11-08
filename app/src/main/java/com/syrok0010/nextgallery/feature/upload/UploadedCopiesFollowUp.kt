package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText

/**
 * Links an uploaded phone file and its new server file as one media object. Memories 10.1 does not
 * return AUID/BUID, so the server copy cannot be matched later; the upload is the only moment both
 * identities are known together. One instance serves one upload batch.
 */
internal class UploadedCopiesFollowUp(
    private val identities: MediaIdentityRegistry,
    private val sessions: SessionStore,
    private val refreshLibrary: () -> Unit,
) : UploadFollowUp {
    /**
     * Links only while [credentials] are still signed in: logout clears Memories identities, and
     * the same numeric file id on another server is a different file.
     */
    internal suspend fun link(
        credentials: AccountCredentials,
        contentUri: String,
        fileId: Long,
    ): Boolean =
        identities.link(
            existing = MediaSourceIdentity(
                MediaSourceKind.Local,
                uploadedLocalIdentityUri(contentUri),
            ),
            linked = MediaSourceIdentity(MediaSourceKind.Memories, fileId.toString()),
            isCurrent = {
                (sessions.session.value as? SessionUiState.SignedIn)?.credentials == credentials
            },
        )

    private var uploaded = false

    override suspend fun uploaded(credentials: AccountCredentials, file: UploadedFile) {
        uploaded = true
        // Without the link the timeline shows the phone file and its server copy separately.
        file.fileId?.let { fileId -> link(credentials, file.selection.contentUri, fileId) }
    }

    /** Shows the server copies without waiting for a manual refresh. */
    override suspend fun finished(missed: List<UploadSelection>): List<UiText> {
        if (uploaded) refreshLibrary()
        return emptyList()
    }
}

/** Folder assets read an exact volume, but the device index registers aggregate external URIs. */
private fun uploadedLocalIdentityUri(contentUri: String): String {
    val match = externalMediaUri.matchEntire(contentUri) ?: return contentUri
    if (match.groupValues[1] == "internal") return contentUri
    return "content://media/external/${match.groupValues[2]}/media/${match.groupValues[3]}"
}

private val externalMediaUri = Regex("content://media/([^/]+)/(images|video)/media/([0-9]+)")

/**
 * File id from the `OC-FileId` response header: the id zero-padded to eight digits, followed by
 * the instance id, which always starts with "oc".
 */
internal fun uploadedFileId(header: String?): Long? =
    header
        ?.takeWhile(Char::isDigit)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
