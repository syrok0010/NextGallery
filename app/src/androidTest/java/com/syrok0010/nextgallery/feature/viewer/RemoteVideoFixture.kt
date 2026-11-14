package com.syrok0010.nextgallery.feature.viewer

import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.core.network.headerMap
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.origin
import java.util.concurrent.CopyOnWriteArrayList
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest
import okhttp3.Credentials
import okio.Buffer

/** A small Memories /stream fixture, including authentication and byte-range responses. */
internal class RemoteVideoFixture(private val bytes: ByteArray) : AutoCloseable {
    /** Lower-case headers of each request; `:request` holds its method and target. */
    val requests = CopyOnWriteArrayList<Map<String, String>>()

    @Volatile var corruptOriginal = false

    @Volatile var hls = false

    @Volatile var hlsStatus = 200

    @Volatile var status = 200

    @Volatile var authorization = Credentials.basic("fixture", "password")

    private val server = loopbackServer(::serve)
    val url = server.origin

    private fun serve(request: RecordedRequest): MockResponse {
        val headers = request.headerMap
        requests += headers + (":request" to "${request.method} ${request.target}")
        val path = request.target.substringBefore('?')
        val isStream = request.method == "GET" && request.target == "/apps/memories/api/stream/42"
        val isConfig = path == "/apps/memories/api/config"
        val isHls = hls && path.startsWith("/apps/memories/api/video/transcode/")
        val code = when {
            !isStream && !isConfig && !isHls -> 404
            headers["authorization"] != authorization -> 401
            isConfig -> 200
            isHls -> hlsStatus
            else -> status
        }
        if (code != 200) return MockResponse.Builder().code(code).build()
        val payload = when {
            isConfig -> """{"version":"7","vod_disable":${!hls}}""".toByteArray()

            isHls && path.endsWith("/index.m3u8") ->
                "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\n360p.m3u8\n"
                    .toByteArray()

            isHls ->
                InstrumentationRegistry
                    .getInstrumentation()
                    .context.assets
                    .open("hls/${path.substringAfterLast('/')}")
                    .use { asset -> asset.readBytes() }

            isStream && corruptOriginal -> byteArrayOf(1, 2, 3)

            else -> bytes
        }
        val type = when {
            path.endsWith("m3u8") -> "application/vnd.apple.mpegurl"
            path.endsWith(".ts") -> "video/mp2t"
            else -> "video/mp4"
        }
        val range = headers["range"]?.removePrefix("bytes=")
        val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
        val end = (range?.substringAfter('-')?.toIntOrNull() ?: payload.lastIndex)
            .coerceAtMost(payload.lastIndex)
        return MockResponse
            .Builder()
            .code(if (range != null) 206 else 200)
            .addHeader("Content-Type", type)
            .addHeader("Accept-Ranges", "bytes")
            .apply {
                if (range != null) addHeader("Content-Range", "bytes $start-$end/${payload.size}")
            }.body(Buffer().write(payload, start, end - start + 1))
            .build()
    }

    override fun close() {
        server.close()
    }
}
