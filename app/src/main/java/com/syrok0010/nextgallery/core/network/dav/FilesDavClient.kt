package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

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

    /** `PROPFIND Depth: 0` of the file; null when it is missing. */
    suspend fun properties(file: DavFilePath): DavFileProperties? =
        withContext(Dispatchers.IO) {
            val credentials = credentials()
            val request = transport
                .authenticatedRequestBuilder(
                    credentials,
                    file.url(credentials.serverUrl).toString(),
                    accept = "application/xml",
                ).method(
                    PROPFIND,
                    PROPFIND_FILE_PROPERTIES.toRequestBody(XML_MEDIA_TYPE.toMediaType()),
                ).header("Depth", "0")
                .build()
            transport.baseClient.newCall(request).await().use { response ->
                when {
                    response.code == 207 -> response.body?.byteStream()?.use(::fileProperties)
                        ?: DavFileProperties(size = null, fileId = null, etag = null)

                    response.code == 404 -> null

                    else -> throw DavHttpException(response.code, PROPFIND)
                }
            }
        }

    /**
     * Streams the file to [read] only while it still has [etag], so the bytes belong to the version
     * whose properties were read. Returns [ReadFileResult.Changed] once the file was replaced or
     * removed.
     */
    suspend fun <T> readFile(
        file: DavFilePath,
        etag: String?,
        read: suspend (InputStream) -> T,
    ): ReadFileResult<T> =
        withContext(Dispatchers.IO) {
            val credentials = credentials()
            val request = transport
                .authenticatedRequestBuilder(
                    credentials,
                    file.url(credentials.serverUrl).toString(),
                    accept = "*/*",
                ).get()
                .apply { etag?.let { header("If-Match", "\"$it\"") } }
                .build()
            transport.baseClient.newCall(request).await().use { response ->
                when {
                    response.isSuccessful -> ReadFileResult.Read(
                        response.body?.byteStream()?.use {
                            read(
                                it,
                            )
                        } ?: throw IOException("No DAV body"),
                    )

                    response.code == 404 || response.code == 412 -> ReadFileResult.Changed

                    else -> throw DavHttpException(response.code, GET)
                }
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
        const val GET = "GET"
        const val DELETE = "DELETE"
        const val PROPFIND = "PROPFIND"
        const val XML_MEDIA_TYPE = "application/xml; charset=utf-8"
        const val PROPFIND_FILE_PROPERTIES =
            "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:prop>" +
                "<d:getcontentlength/><d:getetag/><oc:fileid/></d:prop></d:propfind>"
    }
}

enum class DeleteFileResult { Deleted, Missing, Conflict }

/** Values from one successful `propstat`; each is null when the server did not return it. */
data class DavFileProperties(val size: Long?, val fileId: Long?, val etag: String?)

sealed interface ReadFileResult<out T> {
    data class Read<T>(val value: T) : ReadFileResult<T>

    /** The file no longer has the requested ETag or is gone. */
    data object Changed : ReadFileResult<Nothing>
}

private const val DAV_NAMESPACE = "DAV:"
private const val OWNCLOUD_NAMESPACE = "http://owncloud.org/ns"

/** Reads the first successful `propstat` of a multistatus body; external entities stay off. */
internal fun fileProperties(body: InputStream): DavFileProperties {
    val builder = DocumentBuilderFactory
        .newInstance()
        .apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }.newDocumentBuilder()
    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
    val document = builder.parse(body)
    if (document.doctype != null) throw IOException("DAV response declares a DOCTYPE")
    val propstats = document.getElementsByTagNameNS(DAV_NAMESPACE, "propstat")
    val successful = (0 until propstats.length)
        .map { propstats.item(it) as Element }
        .firstOrNull { propstat ->
            propstat.text(DAV_NAMESPACE, "status")?.split(Regex("\\s+"))?.getOrNull(1) == "200"
        }
    return DavFileProperties(
        size = successful?.text(DAV_NAMESPACE, "getcontentlength")?.toLongOrNull(),
        fileId = successful?.text(OWNCLOUD_NAMESPACE, "fileid")?.toLongOrNull()?.takeIf { it > 0L },
        // getetag is a quoted entity tag; If-Match adds the quotes back.
        etag = successful?.text(DAV_NAMESPACE, "getetag")?.removeSurrounding("\"")?.takeIf {
            it.isNotEmpty()
        },
    )
}

private fun Element.text(namespace: String, name: String): String? =
    getElementsByTagNameNS(namespace, name).item(0)?.textContent?.trim()

/**
 * File id from the `OC-FileId` response header: the id zero-padded to eight digits, followed by
 * the instance id, which always starts with "oc".
 */
internal fun ocFileId(header: String?): Long? =
    header
        ?.takeWhile(Char::isDigit)
        ?.toLongOrNull()
        ?.takeIf { it > 0L }
