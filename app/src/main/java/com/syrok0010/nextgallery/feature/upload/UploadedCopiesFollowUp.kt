package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.UiText

/**
 * Links a phone file and its uploaded or already existing server copy as one media object.
 * The explicit link does not depend on matching AUID/BUID; upload knows both source identities. One instance serves one upload batch.
 */
internal class UploadedCopiesFollowUp(
    private val identities: MediaIdentityRegistry,
    private val sessions: SessionStore,
) {
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

/** Exact-volume reads and aggregate device-index identities have different URI roles. */
internal fun uploadedLocalIdentityUri(uri: String): String {
    val match = Regex(
        "content://media/([^/]+)/(images|video)/media/([0-9]+)",
    ).matchEntire(uri) ?: return uri
    if (match.groupValues[1] == "internal") return uri
    return "content://media/external/${match.groupValues[2]}/media/${match.groupValues[3]}"
}
