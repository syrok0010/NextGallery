package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesMultipreviewHttpTest {
    @Test fun `previews are emitted before the response ends`() =
        runBlocking {
            val firstReceived = CountDownLatch(1)
            val seenBeforeSecond = AtomicBoolean(false)
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.execute {
                        server.accept().use { socket ->
                            val reader = socket.getInputStream().bufferedReader()
                            val length = generateSequence {
                                reader.readLine()?.takeIf(
                                    String::isNotEmpty,
                                )
                            }.first { it.startsWith("Content-Length:", ignoreCase = true) }
                                .substringAfter(':')
                                .trim()
                                .toInt()
                            generateSequence {
                                reader.readLine()?.takeIf(
                                    String::isNotEmpty,
                                )
                            }.count()
                            repeat(length) { reader.read() }
                            socket.getOutputStream().apply {
                                write(
                                    "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                                        .toByteArray(),
                                )
                                write(block(reqid = 1, image = byteArrayOf(1)))
                                flush()
                                // The second preview is written only after the client has seen the first.
                                seenBeforeSecond.set(firstReceived.await(5, TimeUnit.SECONDS))
                                write(block(reqid = 2, image = byteArrayOf(2)))
                                flush()
                            }
                        }
                    }
                    val credentials =
                        AccountCredentials(
                            "http://127.0.0.1:${server.localPort}",
                            "fixture",
                            "password",
                            "fixture",
                        )
                    val client =
                        MemoriesApiClient(
                            NextcloudTransport(Json { ignoreUnknownKeys = true }),
                        ) { credentials }
                    // Blocks carry reqid 1 and 2; the client maps them back to these file ids.
                    val received = mutableListOf<Long>()
                    client.multipreview(listOf(11L, 22L), 256, 256).collect { preview ->
                        received += preview.fileId
                        firstReceived.countDown()
                    }

                    assertEquals(listOf(11L, 22L), received)
                    assertTrue(seenBeforeSecond.get())
                } finally {
                    firstReceived.countDown()
                    executor.shutdownNow()
                }
            }
        }

    @Test fun `http failure fails the stream`() =
        runBlocking {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
                val executor = Executors.newSingleThreadExecutor()
                try {
                    executor.execute {
                        server.accept().use { socket ->
                            socket.getOutputStream().write(
                                "HTTP/1.1 503 Busy\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                    .toByteArray(),
                            )
                        }
                    }
                    val credentials =
                        AccountCredentials(
                            "http://127.0.0.1:${server.localPort}",
                            "fixture",
                            "password",
                            "fixture",
                        )
                    val client = MemoriesApiClient(NextcloudTransport(Json)) { credentials }
                    val error = runCatching {
                        client.multipreview(listOf(1L), 256, 256).toList()
                    }.exceptionOrNull()
                    assertTrue(error is IllegalStateException && "503" in error.message.orEmpty())
                } finally {
                    executor.shutdownNow()
                }
            }
        }

    private fun block(reqid: Int, image: ByteArray): ByteArray {
        val header = """{"reqid":$reqid,"len":${image.size},"type":"image/jpeg"}"""
            .encodeToByteArray()
        return byteArrayOf(header.size.toByte()) + header + image
    }
}
