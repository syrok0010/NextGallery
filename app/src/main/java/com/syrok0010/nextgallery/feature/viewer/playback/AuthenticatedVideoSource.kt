package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response

/** Runs for every open, range request and retry; no credential snapshot is retained by the player. */
internal class AuthenticatedVideoSource(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val account = credentials()
        val url = request.url
        val server = NextcloudTransport.normalizeServerOrigin(account.serverUrl).toHttpUrl()
        val apiPath = server.encodedPath.trimEnd('/') + "/apps/memories/api/"
        if (url.scheme != server.scheme ||
            url.host != server.host ||
            url.port != server.port ||
            url.username.isNotEmpty() ||
            url.password.isNotEmpty() ||
            !url.encodedPath.startsWith(apiPath)
        ) throw IOException("Invalid video server")

        val authenticated = transport.authenticatedRequestBuilder(account, url.toString(), "*/*")
            .get()
            .apply {
                request.header("Range")?.let { header("Range", it) }
                request.header("User-Agent")?.let { header("User-Agent", it) }
            }
            .build()
        return chain.proceed(authenticated)
    }
}
