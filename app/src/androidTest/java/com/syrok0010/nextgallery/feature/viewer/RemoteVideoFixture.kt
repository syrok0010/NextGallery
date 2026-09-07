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
        val code = when {
            !isStream -> 404
            headers["authorization"] != authorization -> 401
            else -> status
        }
        val output = it.getOutputStream()
        if (code != 200) {
            output.write("HTTP/1.1 $code Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val range = headers["range"]?.removePrefix("bytes=")
            val start = range?.substringBefore('-')?.toIntOrNull() ?: 0
            val end = (range?.substringAfter('-')?.toIntOrNull() ?: bytes.lastIndex).coerceAtMost(bytes.lastIndex)
            val partial = range != null
            output.write(buildString {
                append("HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n")
                append("Content-Type: video/mp4\r\nAccept-Ranges: bytes\r\n")
                if (partial) append("Content-Range: bytes $start-$end/${bytes.size}\r\n")
                append("Content-Length: ${end - start + 1}\r\nConnection: close\r\n\r\n")
            }.toByteArray())
            output.write(bytes, start, end - start + 1)
        }
        output.flush()
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}
