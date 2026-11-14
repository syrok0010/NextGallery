package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.origin
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoriesMultipreviewHttpTest {
    @Test fun `previews are emitted before the response ends`() =
        runBlocking {
            val firstReceived = CountDownLatch(1)
            val seenBeforeSecond = AtomicBoolean(false)
            val first = block(reqid = 1, image = byteArrayOf(1))
            val second = block(reqid = 2, image = byteArrayOf(2))
            val server = loopbackServer {
                MockResponse
                    .Builder()
                    .addHeader("Content-Type", "application/octet-stream")
                    .body(object : MockResponseBody {
                        override val contentLength = (first.size + second.size).toLong()

                        override fun writeTo(sink: BufferedSink) {
                            sink.write(first).flush()
                            // The second preview is written only after the client has seen the first.
                            seenBeforeSecond.set(firstReceived.await(5, TimeUnit.SECONDS))
                            sink.write(second).flush()
                        }
                    })
                    .build()
            }
            server.use {
                // Blocks carry reqid 1 and 2; the client maps them back to these file ids.
                val received = mutableListOf<Long>()
                try {
                    client(server, Json { ignoreUnknownKeys = true })
                        .multipreview(listOf(11L, 22L), 256, 256)
                        .collect { preview ->
                            received += preview.fileId
                            firstReceived.countDown()
                        }
                } finally {
                    firstReceived.countDown()
                }

                assertEquals(listOf(11L, 22L), received)
                assertTrue(seenBeforeSecond.get())
            }
        }

    @Test fun `http failure fails the stream`() =
        runBlocking {
            loopbackServer { MockResponse.Builder().code(503).build() }.use { server ->
                val error = runCatching {
                    client(server, Json).multipreview(listOf(1L), 256, 256).toList()
                }.exceptionOrNull()
                assertTrue(error is IllegalStateException && "503" in error.message.orEmpty())
            }
        }

    private fun client(server: MockWebServer, json: Json): MemoriesApiClient {
        val credentials = AccountCredentials(server.origin, "fixture", "password", "fixture")
        return MemoriesApiClient(NextcloudTransport(json)) { credentials }
    }

    private fun block(reqid: Int, image: ByteArray): ByteArray {
        val header = """{"reqid":$reqid,"len":${image.size},"type":"image/jpeg"}"""
            .encodeToByteArray()
        return byteArrayOf(header.size.toByte()) + header + image
    }
}
