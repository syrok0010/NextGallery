package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.upload.UploadFollowUp
import com.syrok0010.nextgallery.feature.upload.UploadJob
import com.syrok0010.nextgallery.feature.upload.UploadedFile

/** The journal owns retry; the albums module owns membership and its observable state. */
internal class AlbumUploadFollowUp(private val albums: Albums) : UploadFollowUp {
    override suspend fun run(
        account: AccountCredentials,
        job: UploadJob,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean = albums.addUploaded(account, checkNotNull(job.album), file, recovered)
}
