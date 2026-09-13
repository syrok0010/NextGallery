package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.feature.images.MemoriesAssetUrlFactory
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import java.io.IOException
import okhttp3.Interceptor
import okhttp3.Response

/** Runs for every open, range request and retry; no credential snapshot is retained by the player. */
internal class AuthenticatedVideoSource(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val segments = request.url.pathSegments
        if (request.url.host != "memories.invalid") throw IOException("Invalid video source")
        val account = credentials()
        val base = NextcloudTransport.normalizeServerOrigin(account.serverUrl)
        val url = when {
            segments.size == 2 && segments[0] == "original" && segments[1].toLongOrNull() != null ->
                MemoriesAssetUrlFactory.urlsFor(MediaAssetRef.MemoriesFile(segments[1].toLong()), account.serverUrl).originalUrl
            segments == listOf("config") -> "$base/apps/memories/api/config"
            segments.size == 4 && segments[0] == "vod" &&
                segments[1].matches(Regex("[a-zA-Z0-9-]{8,}")) && segments[2].toLongOrNull() != null &&
                segments[3].matches(Regex("[a-zA-Z0-9_.-]+")) ->
                "$base/apps/memories/api/video/transcode/${segments.drop(1).joinToString("/")}" +
                    (request.url.encodedQuery?.let { "?$it" } ?: "")
            else -> throw IOException("Invalid video source")
        }
        val authenticated = transport.authenticatedRequestBuilder(account, url, "*/*")
            .get()
            .apply {
                request.header("Range")?.let { header("Range", it) }
                request.header("User-Agent")?.let { header("User-Agent", it) }
            }
            .build()
        // Media3 resolves relative HLS playlists/segments against this logical request.
        return chain.proceed(authenticated).newBuilder().request(request).build()
    }
}
