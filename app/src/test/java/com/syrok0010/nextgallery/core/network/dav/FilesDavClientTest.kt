package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class FilesDavClientTest {
    private val file = DavFilePath.of("anna", "/Photos/Camera", "a/b.jpg")

    @Test fun `create puts without overwrite and reads the file id`() =
        runBlocking {
            Fixture { Response(201, mapOf("OC-FileId" to "00000183ocmsigiy9k16")) }.use { fixture ->
                assertEquals(
                    CreateFileResult.Created(183),
                    fixture.client.createFile(file, "x".toRequestBody()),
                )
                val request = fixture.requests.single()
                assertEquals("PUT", request.method)
                assertEquals("*", request.headers["if-none-match"])
                // A slash in the name stays inside one segment.
                assertEquals("/remote.php/dav/files/anna/Photos/Camera/a%2Fb.jpg", request.target)
            }
        }

    @Test fun `create reports an existing file and fails on other statuses`() =
        runBlocking {
            var code = 412
            Fixture { Response(code) }.use { fixture ->
                assertEquals(
                    CreateFileResult.AlreadyExists,
                    fixture.client.createFile(file, "x".toRequestBody()),
                )
                code = 507
                val error = runCatching {
                    fixture.client.createFile(
                        file,
                        "x".toRequestBody(),
                    )
                }.exceptionOrNull()
                assertEquals(507, (error as DavHttpException).code)
            }
        }

    @Test fun `size reads content length and treats a missing file as unknown`() =
        runBlocking {
            var code = 200
            Fixture { Response(code, mapOf("Content-Length" to "2048")) }.use { fixture ->
                assertEquals(2048L, fixture.client.size(file))
                assertEquals("HEAD", fixture.requests.single().method)
                code = 404
                assertNull(fixture.client.size(file))
            }
        }

    @Test fun `file id is read from the OC-FileId header`() {
        assertEquals(183L, ocFileId("00000183ocmsigiy9k16"))
        assertEquals(123_456_789L, ocFileId("123456789ocmsigiy9k16"))
        assertNull(ocFileId(null))
        assertNull(ocFileId(""))
        assertNull(ocFileId("ocmsigiy9k16"))
        assertNull(ocFileId("00000000ocmsigiy9k16"))
    }

    private data class Recorded(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    private data class Response(val code: Int, val headers: Map<String, String> = emptyMap())

    private class Fixture(private val respond: (Recorded) -> Response) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        val requests = CopyOnWriteArrayList<Recorded>()
        private val credentials =
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
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence {
                            reader.readLine()?.takeIf(
                                String::isNotEmpty,
                            )
                        }.toList()
                        val (method, target) = lines.first().split(' ')
                        val request = Recorded(
                            method,
                            target,
                            lines.drop(1).associate { line ->
                                line
                                    .substringBefore(
                                        ':',
                                    ).lowercase() to line.substringAfter(':').trim()
                            },
                        )
                        request.headers["content-length"]?.toInt()?.let { length ->
                            reader.read(
                                CharArray(length),
                            )
                        }
                        requests += request
                        val response = respond(request)
                        val headers = (mapOf("Content-Length" to "0") + response.headers)
                            .entries
                            .joinToString("") { (name, value) -> "$name: $value\r\n" }
                        it.getOutputStream().apply {
                            write(
                                "HTTP/1.1 ${response.code} Fixture\r\n${headers}Connection: close\r\n\r\n"
                                    .toByteArray(),
                            )
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
}
