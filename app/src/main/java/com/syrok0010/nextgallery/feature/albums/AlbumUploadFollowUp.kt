package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.upload.UploadFollowUp
import com.syrok0010.nextgallery.feature.upload.UploadJob
import com.syrok0010.nextgallery.feature.upload.UploadedFile

/** Adds an uploaded file to the album picked together with it. */
internal class AlbumUploadFollowUp(
    private val writer: MemoriesAlbumWriter,
    private val catalog: AlbumCatalogRepository,
) : UploadFollowUp {
    /**
     * COPY answers 409 when the album holds a file of that name. On the first attempt a conflict in
     * an existing album means the file is there already. After an interrupted attempt the earlier
     * COPY may or may not have landed, so the album is searched for the exact file id first, and a
     * conflict after a negative check stays unclear.
     */
    override suspend fun run(
        account: AccountCredentials,
        job: UploadJob,
        file: UploadedFile,
        recovered: Boolean,
    ): Boolean {
        val album = checkNotNull(job.album).let { AlbumAddress(it.owner, it.name) }
        val added = if (recovered) {
            writer.containsFile(file.path, album, file.fileId) ||
                writer.addFile(file.path, album) == AlbumCopyOutcome.Added
        } else {
            writer.addFile(file.path, album) != AlbumCopyOutcome.Failed
        }
        // The album sheet refreshed the catalog before the file reached the album.
        catalog.refresh()
        return added
    }
}
