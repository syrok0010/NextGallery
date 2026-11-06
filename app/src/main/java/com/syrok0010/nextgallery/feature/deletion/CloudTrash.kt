package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.capabilities.NextcloudCapabilitiesClient
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

/** What the server said about its trash bin when the delete dialog asked. */
internal enum class CloudTrashStatus {
    /** No answer yet: confirming now deletes only phone copies. */
    Checking,

    /** `files.undelete` is true: cloud copies go to the Nextcloud trash. */
    Available,

    /** The server answered without `files.undelete: true`: cloud copies are deleted for good. */
    Missing,

    /** No usable answer: the cloud is not touched. */
    Unreachable,
    ;

    /** Cloud copies are deleted only after the server has answered. */
    val allowsCloud: Boolean get() = this == Available || this == Missing
}

/** Asks the server once per delete dialog, so the answer is fresh when the user decides. */
internal class NextcloudTrashBin(private val client: NextcloudCapabilitiesClient) {
    suspend fun status(): CloudTrashStatus =
        try {
            if (client.capabilities().files?.undelete == true) {
                CloudTrashStatus.Available
            } else {
                CloudTrashStatus.Missing
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            CloudTrashStatus.Unreachable
        }
}
