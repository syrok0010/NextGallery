package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState

/**
 * Links a phone file and its uploaded or already existing server copy as one media object.
 * The explicit link does not depend on matching AUID/BUID; upload knows both source identities.
 */
internal class UploadedCopiesFollowUp(
    private val identities: MediaIdentityRegistry,
    private val sessions: SessionStore,
) : UploadFollowUp {
    /** Without a server file id there is nothing exact to link. Linking again is harmless. */
    override suspend fun run(
        account: AccountCredentials,
        job: UploadJob,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean = file.fileId?.let { link(account, job.selection.identityUri, it) } == true

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
