package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.serialization.json.Json

internal class DavRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal class DavResponseStub(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    constructor(code: Int, headers: Map<String, String> = emptyMap(), body: String) :
        this(code, headers, body.toByteArray())
}

/** One-connection-at-a-time HTTP/1.1 server for DAV client tests; user is `anna`. */
internal class DavFixtureServer(private val respond: (DavRequest) -> DavResponseStub) :
    AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val executor = Executors.newSingleThreadExecutor()
    val requests = CopyOnWriteArrayList<DavRequest>()
    val credentials =
        AccountCredentials(
            "http://127.0.0.1:${server.localPort}",
            "fixture",
            "password",
            userId = "anna",
        )
    val client = FilesDavClient(NextcloudTransport(Json)) { credentials }

    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                socket.use {
                    val input = it.getInputStream()
                    val lines = input.headerLines()
                    val (method, target) = lines.first().split(' ')
                    val headers = lines.drop(1).associate { line ->
                        line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                    }
                    val length = headers["content-length"]?.toInt() ?: 0
                    val request =
                        DavRequest(method, target, headers, input.readNBytesCompat(length))
                    requests += request
                    val response = respond(request)
                    val head = (
                        mapOf(
                            "Content-Length" to response.body.size.toString(),
                        ) +
                            response.headers
                        ).entries
                        .joinToString("") { (name, value) -> "$name: $value\r\n" }
                    it.getOutputStream().apply {
                        write(
                            "HTTP/1.1 ${response.code} Fixture\r\n${head}Connection: close\r\n\r\n"
                                .toByteArray(),
                        )
                        write(response.body)
                        flush()
                    }
                }
            }
        }
    }

    override fun close() {
        server.close()
        executor.shutdownNow()
    }
}

private fun InputStream.headerLines(): List<String> {
    val lines = mutableListOf<String>()
    val line = ByteArrayOutputStream()
    while (true) {
        val byte = read()
        if (byte < 0) break
        if (byte == '\n'.code) {
            val text = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
            if (text.isEmpty()) break
            lines += text
            line.reset()
        } else {
            line.write(byte)
        }
    }
    return lines
}

private fun InputStream.readNBytesCompat(length: Int): ByteArray {
    val buffer = ByteArray(length)
    var total = 0
    while (total < length) {
        val count = read(buffer, total, length - total)
        if (count < 0) break
        total += count
    }
    return buffer.copyOf(total)
}
