package com.syrok0010.nextgallery.feature.upload

import androidx.annotation.StringRes
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

/** Returns null if an existing name cannot be confirmed as a file of the same size. */
internal fun uploadFile(
    transport: NextcloudTransport,
    credentials: AccountCredentials,
    url: HttpUrl,
    selection: UploadSelection,
    body: RequestBody,
): UploadedFile? {
    val request = transport
        .authenticatedRequestBuilder(credentials, url.toString(), accept = "*/*")
        .method("PUT", body)
        .header("If-None-Match", "*")
        .build()
    val (existed, fileId) = transport.baseClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful && response.code != 412) {
            throw UploadException(R.string.upload_error_http, response.code, selection.displayName)
        }
        (response.code == 412) to uploadedFileId(response.header("OC-FileId"))
    }
    if (!existed) return UploadedFile(selection, url, created = true, fileId)
    val length = body.contentLength()
    if (length < 0L || !sameRemoteSize(transport, credentials, url, length)) return null
    // A 412 has no OC-FileId, and Nextcloud 34 also omits it from HEAD. Size equality is the
    // product's accepted identity heuristic; only then ask DAV for the explicit link (#131).
    return UploadedFile(
        selection,
        url,
        created = false,
        existingFileId(transport, credentials, url),
    )
}

private fun sameRemoteSize(
    transport: NextcloudTransport,
    credentials: AccountCredentials,
    url: HttpUrl,
    length: Long,
): Boolean {
    val request = transport
        .authenticatedRequestBuilder(credentials, url.toString(), accept = "*/*")
        .head()
        .build()
    return runCatching {
        transport.baseClient.newCall(request).execute().use { response ->
            response.isSuccessful && response.header("Content-Length")?.toLongOrNull() == length
        }
    }.getOrDefault(false)
}

private fun existingFileId(
    transport: NextcloudTransport,
    credentials: AccountCredentials,
    url: HttpUrl,
): Long? =
    runCatching {
        val request = transport
            .authenticatedRequestBuilder(credentials, url.toString(), accept = "application/xml")
            .method(
                "PROPFIND",
                """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>"""
                    .toRequestBody("application/xml; charset=utf-8".toMediaType()),
            ).header("Depth", "0")
            .build()
        transport.baseClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val builder = DocumentBuilderFactory
                .newInstance()
                .apply {
                    isNamespaceAware = true
                    isExpandEntityReferences = false
                }.newDocumentBuilder()
            builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
            val document = response.body?.byteStream()?.let { builder.parse(it) } ?: return@use null
            if (document.doctype != null) return@use null
            val properties = document.getElementsByTagNameNS("DAV:", "propstat")
            (0 until properties.length).firstNotNullOfOrNull { index ->
                val propstat = properties.item(index) as Element
                val status = propstat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent
                if (status?.trim()?.split(Regex("\\s+"))?.getOrNull(1) != "200") {
                    null
                } else {
                    propstat
                        .getElementsByTagNameNS("http://owncloud.org/ns", "fileid")
                        .item(0)
                        ?.textContent
                        ?.trim()
                        ?.toLongOrNull()
                        ?.takeIf { it > 0L }
                }
            }
        }
    }.getOrNull()

internal class UploadException(@StringRes val reason: Int, vararg val args: Any) : Exception()
