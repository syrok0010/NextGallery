package com.syrok0010.nextgallery.feature.viewer

import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A small Memories /stream fixture, including authentication and byte-range responses. */
internal class RemoteVideoFixture(private val bytes: ByteArray) : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${server.localPort}"
    val requests = CopyOnWriteArrayList<Map<String, String>>()
    @Volatile var corruptOriginal = false
    @Volatile var hls = false
    @Volatile var hlsStatus = 200
    @Volatile var status = 200
    @Volatile var authorization = okhttp3.Credentials.basic("fixture", "password")

    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                executor.execute { try { serve(socket) } catch (_: java.io.IOException) { socket.close() } }
            }
        }
    }

    private fun serve(socket: Socket) = socket.use {
        val reader = it.getInputStream().bufferedReader()
        val request = reader.readLine() ?: return@use
        val headers = mutableMapOf(":request" to request)
        while (true) {
            val line = reader.readLine()?.takeIf(String::isNotEmpty) ?: break
            headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
        }
        requests += headers
        val isStream = request.startsWith("GET /apps/memories/api/stream/42 ")
        val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?')
        val isConfig = path == "/apps/memories/api/config"
        val isHls = hls && path.startsWith("/apps/memories/api/video/transcode/")
        val payload = when {
            isConfig -> """{"version":"7","vod_disable":${!hls}}""".toByteArray()
            isHls && path.endsWith("/index.m3u8") -> "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=500000,RESOLUTION=640x360\n360p.m3u8\n".toByteArray()
            isHls -> androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context.assets
                .open("hls/${path.substringAfterLast('/')}").use { asset -> asset.readBytes() }
            isStream && corruptOriginal -> byteArrayOf(1, 2, 3)
            else -> bytes
        }
        val code = when {
            !isStream && !isConfig && !isHls -> 404
            headers["authorization"] != authorization -> 401
            isConfig -> 200
            isHls -> hlsStatus
            else -> status
        }
        val output = it.getOutputStream()
        if (code != 200) {
            output.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val range = headers["range"]?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: payload.lastIndex).coerceAtMost(payload.lastIndex)
            val partial = range != null
            output.write(buildString {
                append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                val type = if (path.endsWith("m3u8")) "application/vnd.apple.mpegurl" else if (path.endsWith(".ts")) "video/mp2t" else "video/mp4"
                append("Content-Type: $type\r\nAccept-Ranges: bytes\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${payload.size}\r\n")
                append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
            }.toByteArray())
            output.write(payload, start, end - start + 1)
        }
        output.flush()
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
