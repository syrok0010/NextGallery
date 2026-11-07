package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.coroutines.executeAsync

/**
 * File in a user's DAV files root. [segments] are decoded path segments, so a name containing
 * `/` stays a single segment.
 */
data class DavFilePath(val user: String, val segments: List<String>) {
    init {
        require(segments.isNotEmpty()) { "A DAV file path needs a name" }
    }

    val name: String get() = segments.last()

    companion object {
        /** [path] is relative to the files root, as Memories image info reports it. */
        fun of(user: String, path: String): DavFilePath =
            DavFilePath(user, path.split('/').filter(String::isNotEmpty))

        fun of(user: String, directory: String, name: String): DavFilePath =
            DavFilePath(user, directory.split('/').filter(String::isNotEmpty) + name)
    }
}

/** Photos album collection `owner/name`, as in Memories `cluster_id`. */
data class AlbumPath(val owner: String, val name: String)

/** A DAV status the called operation does not define a result for. */
class DavHttpException(val code: Int, method: String) : IOException("HTTP $code on $method")

internal class DavResponse(val code: Int, private val headers: Headers) {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? = headers[name]

    fun unexpected(method: String): Nothing = throw DavHttpException(code, method)
}

/** The URL is built from the same [credentials] that sign the request. */
internal suspend fun NextcloudTransport.dav(
    credentials: AccountCredentials,
    method: String,
    headers: Map<String, String> = emptyMap(),
    body: RequestBody? = null,
    url: (serverUrl: String) -> HttpUrl,
): DavResponse {
    val builder = authenticatedRequestBuilder(
        credentials,
        url(credentials.serverUrl).toString(),
        accept = "*/*",
    ).method(method, body)
    headers.forEach { (name, value) -> builder.header(name, value) }
    return baseClient.newCall(builder.build()).executeAsync().use { response ->
        DavResponse(response.code, response.headers)
    }
}

internal fun davRoot(serverUrl: String): HttpUrl.Builder =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")

internal fun DavFilePath.url(serverUrl: String): HttpUrl =
    davRoot(serverUrl)
        .addPathSegment("files")
        .addPathSegment(user)
        .apply { segments.forEach(::addPathSegment) }
        .build()

/**
 * Awaits the response and hands it to [block], closing it afterwards. Cancelling the coroutine also
 * cancels the call while [block] still reads the body, so a stalled download does not outlive it,
 * and the coroutine ends cancelled rather than with the aborted call's I/O error.
 */
internal suspend fun <T> Call.awaitUse(block: suspend (Response) -> T): T =
    coroutineScope {
        val call = this@awaitUse
        val abort = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.executeAsync().use { block(it) }
        } catch (error: IOException) {
            // The aborted call fails while reading; report the cancellation instead.
            ensureActive()
            throw error
        } finally {
            abort.cancel()
        }
    }
