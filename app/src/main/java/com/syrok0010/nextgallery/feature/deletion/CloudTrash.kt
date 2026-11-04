package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DeleteFileResult
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import kotlinx.coroutines.CancellationException

/** Deletes one cloud copy while preserving the path and ETag safety checks. */
internal fun interface CloudTrash {
    suspend fun trash(credentials: AccountCredentials, fileId: Long): Boolean
}

/** Memories image ids are resolved to DAV paths before the endpoint client deletes them. */
internal class MemoriesCloudTrash(
    private val dav: FilesDavClient,
    private val files: MemoriesFiles,
) : CloudTrash {
    override suspend fun trash(credentials: AccountCredentials, fileId: Long): Boolean {
        try {
            val file = files.file(fileId)
            return when (dav.deleteFile(DavFilePath.of(credentials.userId, file.path), file.etag)) {
                DeleteFileResult.Deleted, DeleteFileResult.Missing -> true
                DeleteFileResult.Conflict -> false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
    }
}
