package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
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

    /** Size from HEAD `Content-Length`, or null when the file is missing or reports no size. */
    suspend fun size(file: DavFilePath): Long? {
        val response = transport.dav(credentials(), HEAD) { file.url(it) }
        return when {
            response.isSuccessful -> response.header("Content-Length")?.toLongOrNull()
            response.code == 404 -> null
            else -> response.unexpected(HEAD)
        }
    }

    /** Reads the existing id only when the same DAV property set confirms its size. */
    suspend fun fileIdIfSizeMatches(file: DavFilePath, expectedSize: Long): Long? =
        withContext(Dispatchers.IO) {
            if (expectedSize < 0L) return@withContext null
            runCatching {
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
                    if (!response.isSuccessful) return@use null
                    val builder = DocumentBuilderFactory
                        .newInstance()
                        .apply {
                            isNamespaceAware = true
                            isExpandEntityReferences = false
                        }.newDocumentBuilder()
                    builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
                    val document = response.body?.byteStream()?.use(builder::parse) ?: return@use null
                    if (document.doctype != null) return@use null
                    val properties = document.getElementsByTagNameNS(DAV_NAMESPACE, "propstat")
                    (0 until properties.length).firstNotNullOfOrNull { index ->
                        val propstat = properties.item(index) as Element
                        val status = propstat
                            .getElementsByTagNameNS(DAV_NAMESPACE, "status")
                            .item(0)
                            ?.textContent
                        val remoteSize = propstat
                            .getElementsByTagNameNS(DAV_NAMESPACE, "getcontentlength")
                            .item(0)
                            ?.textContent
                            ?.trim()
                            ?.toLongOrNull()
                        val fileId = propstat
                            .getElementsByTagNameNS(OWNCLOUD_NAMESPACE, "fileid")
                            .item(0)
                            ?.textContent
                            ?.trim()
                            ?.toLongOrNull()
                        if (
                            status?.trim()?.split(Regex("\\s+"))?.getOrNull(1) == "200" &&
                            remoteSize == expectedSize &&
                            fileId != null && fileId > 0L
                        ) {
                            fileId
                        } else {
                            null
                        }
                    }
                }
            }.getOrNull()
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
        const val PROPFIND = "PROPFIND"
        const val DAV_NAMESPACE = "DAV:"
        const val OWNCLOUD_NAMESPACE = "http://owncloud.org/ns"
        const val XML_MEDIA_TYPE = "application/xml; charset=utf-8"
        const val PROPFIND_FILE_PROPERTIES =
            "<d:propfind xmlns:d=\"DAV:\" xmlns:oc=\"http://owncloud.org/ns\"><d:prop>" +
                "<d:getcontentlength/><oc:fileid/></d:prop></d:propfind>"
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
