package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.InputStream
import okhttp3.HttpUrl
import okhttp3.RequestBody

/**
 * Nextcloud chunked upload under `remote.php/dav/uploads/{user}/{id}`. Parts stored there outlive
 * a dropped connection; [finish] assembles them into the target file. The server deletes an upload
 * nobody touched for a day. Each call reads [credentials] once; statuses without a result here
 * fail with [DavHttpException].
 */
class UploadsDavClient(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) {
    /** Sizes of the stored parts by number; null when the upload does not exist. */
    suspend fun parts(id: String): Map<Int, Long>? {
        val credentials = credentials()
        return transport.propfind(credentials, upload(credentials, id), depth = 1, ::storedParts)
    }

    /**
     * `MKCOL`. Object storage writes the parts straight into [target], so it has to be named
     * up front and on every part.
     */
    suspend fun start(id: String, target: DavFilePath) {
        val response = send(MKCOL, id, target)
        // 405: the upload already exists.
        if (!response.isSuccessful && response.code != 405) response.unexpected(MKCOL)
    }

    /** Stores part [number] of a file of [total] bytes; false when the upload is gone. */
    suspend fun putPart(
        id: String,
        number: Int,
        target: DavFilePath,
        total: Long,
        body: RequestBody,
    ): Boolean {
        val response = send(PUT, id, target, number.toString(), total, body)
        return when {
            response.isSuccessful -> true

            // 412: object storage lost the state of the upload.
            response.code == 404 || response.code == 412 -> false

            else -> response.unexpected(PUT)
        }
    }

    /**
     * Assembles the parts at [target] with `Overwrite: F`, so an existing file is reported, never
     * replaced. Null when the upload is gone.
     */
    suspend fun finish(id: String, target: DavFilePath, total: Long): CreateFileResult? {
        val response = send(MOVE, id, target, ".file", total, overwrite = false)
        return when {
            response.isSuccessful -> CreateFileResult.Created(
                ocFileId(response.header("OC-FileId")),
            )

            response.code == 412 -> CreateFileResult.AlreadyExists

            response.code == 404 -> null

            else -> response.unexpected(MOVE)
        }
    }

    /** Drops the upload and its parts; a missing one is already dropped. */
    suspend fun cancel(id: String) {
        val credentials = credentials()
        val response = transport.dav(credentials, DELETE) { upload(credentials, id) }
        if (!response.isSuccessful && response.code != 404) response.unexpected(DELETE)
    }

    private suspend fun send(
        method: String,
        id: String,
        target: DavFilePath,
        child: String? = null,
        total: Long? = null,
        body: RequestBody? = null,
        overwrite: Boolean? = null,
    ): DavResponse {
        val credentials = credentials()
        val headers = buildMap {
            put("Destination", target.url(credentials.serverUrl).toString())
            total?.let { put("OC-Total-Length", it.toString()) }
            overwrite?.let { put("Overwrite", if (it) "T" else "F") }
        }
        return transport.dav(credentials, method, headers, body) {
            upload(credentials, id).newBuilder().apply { child?.let(::addPathSegment) }.build()
        }
    }

    private fun upload(credentials: AccountCredentials, id: String): HttpUrl =
        davRoot(credentials.serverUrl)
            .addPathSegment("uploads")
            .addPathSegment(credentials.userId)
            .addPathSegment(id)
            .build()

    private companion object {
        const val MKCOL = "MKCOL"
        const val PUT = "PUT"
        const val MOVE = "MOVE"
        const val DELETE = "DELETE"
    }
}

/** Numbered children with a size; the collection itself and `.file` are not parts. */
internal fun storedParts(body: InputStream): Map<Int, Long> =
    decodeMultistatus(body)
        .responses
        .mapNotNull { response ->
            val number = response.href.trim().trimEnd('/').substringAfterLast('/').toIntOrNull()
            val size = response.propstats
                .firstOrNull(
                    PropStat::found,
                )?.prop
                ?.size
                ?.trim()
                ?.toLongOrNull()
            if (number != null && size != null) number to size else null
        }.toMap()
