package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.feature.images.MemoriesAssetUrlFactory
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

internal class VideoAuthenticationRequired : IOException("Video authentication required")

/** Runs for every open, range request and retry; no credential snapshot is retained by the player. */
internal class AuthenticatedVideoSource(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val fileId = request.url.pathSegments.let { segments ->
            if (request.url.host == "memories.invalid" && segments.size == 2 && segments[0] == "original") {
                segments[1].toLongOrNull()
            } else null
        } ?: throw IOException("Invalid video source")
        val account = credentials() ?: throw VideoAuthenticationRequired()
        val url = MemoriesAssetUrlFactory.urlsFor(MediaAssetRef.MemoriesFile(fileId), account.serverUrl).originalUrl
        val authenticated = transport.authenticatedRequestBuilder(account, url, "*/*")
            .get()
            .apply {
                request.header("Range")?.let { header("Range", it) }
                request.header("User-Agent")?.let { header("User-Agent", it) }
            }
            .build()
        return chain.proceed(authenticated)
    }
}
