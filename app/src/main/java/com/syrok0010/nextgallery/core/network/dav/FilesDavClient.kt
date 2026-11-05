package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import okhttp3.RequestBody

sealed interface CreateFileResult {
    /** [fileId] comes from `OC-FileId`; null when the server omits it. */
    data class Created(val fileId: Long?) : CreateFileResult

    data object AlreadyExists : CreateFileResult
}

/**
 * User files under `remote.php/dav/files/{user}`. Each call reads [credentials] once. Statuses
 * without a result here fail with [DavHttpException].
 */
class FilesDavClient(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) {
    /** PUT with `If-None-Match: *`: an existing file is reported, never overwritten. */
    suspend fun createFile(file: DavFilePath, body: RequestBody): CreateFileResult {
        val response = transport.dav(credentials(), PUT, mapOf("If-None-Match" to "*"), body) {
            file.url(it)
        }
        return when {
            response.isSuccessful -> CreateFileResult.Created(
                ocFileId(response.header("OC-FileId")),
            )

            response.code == 412 -> CreateFileResult.AlreadyExists

            else -> response.unexpected(PUT)
        }
    }

    /** Size from HEAD `Content-Length`, or null when the file is missing or reports no size. */
    suspend fun size(file: DavFilePath): Long? {
        val response = transport.dav(credentials(), HEAD) { file.url(it) }
        return when {
            response.isSuccessful -> response.header("Content-Length")?.toLongOrNull()
            response.code == 404 -> null
            else -> response.unexpected(HEAD)
        }
    }

    /** Moves a file to the Nextcloud trash, pinning the path to the looked-up ETag when present. */
    suspend fun deleteFile(file: DavFilePath, etag: String? = null): DeleteFileResult {
        val headers = etag?.let { mapOf("If-Match" to "\"$it\"") }.orEmpty()
        val response = transport.dav(credentials(), DELETE, headers) { file.url(it) }
        return when {
            response.isSuccessful -> DeleteFileResult.Deleted
            response.code == 404 -> DeleteFileResult.Missing
            response.code == 412 -> DeleteFileResult.Conflict
            else -> response.unexpected(DELETE)
        }
    }

    private companion object {
        const val PUT = "PUT"
        const val HEAD = "HEAD"
        const val DELETE = "DELETE"
    }
}

enum class DeleteFileResult { Deleted, Missing, Conflict }

/**
 * File id from the `OC-FileId` response header: the id zero-padded to eight digits, followed by
 * the instance id, which always starts with "oc".
 */
internal fun ocFileId(header: String?): Long? =
    header
        ?.takeWhile(Char::isDigit)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
