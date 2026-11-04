package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody
import okhttp3.Response

data class DavResponse(val code: Int, val headers: Map<String, String>) {
    val isSuccessful: Boolean get() = code in 200..299

    fun header(name: String): String? =
        headers.entries
            .firstOrNull {
                it.key.equals(name, ignoreCase = true)
            }?.value
}

/**
 * Endpoint-level Photos/WebDAV operations. It does not classify product outcomes. Each request
 * reads [credentials] once, so its URL and Authorization header belong to the same session.
 */
class PhotosDavClient(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) {
    suspend fun mkcol(album: AlbumPath): DavResponse =
        execute("MKCOL") { albumUrl(it.serverUrl, album) }

    suspend fun propfind(album: AlbumPath, depth: String = "0"): DavResponse =
        execute("PROPFIND", headers = mapOf("Depth" to depth)) { albumUrl(it.serverUrl, album) }

    suspend fun copy(source: HttpUrl, destination: HttpUrl): DavResponse =
        execute("COPY", headers = mapOf("Destination" to destination.toString())) { source }

    suspend fun put(
        destination: HttpUrl,
        body: RequestBody,
        ifNoneMatch: String? = null,
    ): DavResponse =
        execute(
            "PUT",
            headers = ifNoneMatch?.let { mapOf("If-None-Match" to it) }.orEmpty(),
            body = body,
        ) { destination }

    suspend fun head(destination: HttpUrl): DavResponse = execute("HEAD") { destination }

    private suspend fun execute(
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: RequestBody? = null,
        url: (AccountCredentials) -> HttpUrl,
    ): DavResponse {
        val account = credentials()
        val builder = transport
            .authenticatedRequestBuilder(account, url(account).toString(), accept = "*/*")
            .method(method, body)
        headers.forEach { (name, value) -> builder.header(name, value) }
        return transport.baseClient.newCall(builder.build()).await().use { response ->
            DavResponse(
                code = response.code,
                headers = response.headers.toMultimap().mapValues {
                    it.value.firstOrNull().orEmpty()
                },
            )
        }
    }

    private companion object {
        fun albumUrl(serverUrl: String, album: AlbumPath): HttpUrl =
            davRoot(serverUrl)
                .addPathSegment("photos")
                .addPathSegment(album.owner)
                .addPathSegment("albums")
                .addPathSegment(album.name)
                .build()

        fun davRoot(serverUrl: String): HttpUrl.Builder =
            NextcloudTransport
                .normalizeServerOrigin(serverUrl)
                .toHttpUrl()
                .newBuilder()
                .addPathSegment("remote.php")
                .addPathSegment("dav")
    }
}

data class AlbumPath(val owner: String, val name: String)

fun davFileUrl(serverUrl: String, user: String, path: String): HttpUrl =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")
        .addPathSegment("files")
        .addPathSegment(user)
        .apply { path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }
        .build()

suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )
    }
