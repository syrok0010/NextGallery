package com.syrok0010.nextgallery.core.network

import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response

/** Unlike `execute()`, cancelling the caller cancels the call, so logout stops pending writes. */
internal suspend fun Call.await(): Response =
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

/** [path] is relative to the user's files root, as Memories image info reports it. */
internal fun fileUrl(serverUrl: String, user: String, path: String): HttpUrl =
    davRoot(serverUrl)
        .addPathSegment("files")
        .addPathSegment(user)
        .apply { path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }
        .build()

internal fun davRoot(serverUrl: String): HttpUrl.Builder =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")
