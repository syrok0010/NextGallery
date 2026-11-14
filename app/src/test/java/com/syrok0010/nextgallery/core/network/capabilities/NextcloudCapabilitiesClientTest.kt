package com.syrok0010.nextgallery.core.network.capabilities

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.origin
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okio.BufferedSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException

class NextcloudCapabilitiesClientTest {
    @Test fun `capabilities request is authenticated and HTTP errors fail`() =
        runBlocking {
            var status = 200
            NextcloudFixture { status to capabilities }.use { fixture ->
                val client =
                    NextcloudCapabilitiesClient(fixture.transport) { fixture.credentials }
                assertEquals(
                    NextcloudCapabilitiesDto(FilesCapabilitiesDto(undelete = true)),
                    client.capabilities(),
                )
                val request = fixture.requests.single()
                assertEquals("GET", request.method)
                assertEquals("/ocs/v2.php/cloud/capabilities?format=json", request.target)
                assertEquals("true", request.headers["ocs-apirequest"])
                assertEquals(
                    Credentials.basic("fixture", "password"),
                    request.headers["authorization"],
                )
                status = 503
                val failure = assertThrows(HttpException::class.java) {
                    runBlocking { client.capabilities() }
                }
                assertEquals(503, failure.code())
            }
        }

    @Test fun `delayed response body leaves caller dispatcher available`() =
        runBlocking {
            DelayedBody(capabilities).use { fixture ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                    val result = async(caller) { fixture.source.capabilities() }
                    try {
                        withTimeout(5_000) { fixture.bodyReadStarted.await() }
                        // A second task must run while the capabilities body is still withheld.
                        val heartbeat = async(caller) { true }
                        assertTrue(withTimeout(1_000) { heartbeat.await() })
                        fixture.releaseBody.countDown()
                        assertEquals(
                            true,
                            withTimeout(5_000) { result.await() }.files?.undelete,
                        )
                    } finally {
                        fixture.releaseBody.countDown()
                        result.cancelAndJoin()
                    }
                }
            }
        }

    @Test fun `cancelling while reading delayed body cancels HTTP call promptly`() =
        runBlocking {
            DelayedBody(capabilities).use { fixture ->
                val result = async { fixture.source.capabilities() }
                try {
                    withTimeout(5_000) { fixture.bodyReadStarted.await() }
                    result.cancel()
                    withTimeout(1_000) {
                        fixture.callCancelled.await()
                        result.join()
                    }
                    assertTrue(result.isCancelled)
                } finally {
                    fixture.releaseBody.countDown()
                    result.cancelAndJoin()
                }
            }
        }

    private val capabilities =
        """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":{"files":{"undelete":true}}}}}"""

    /** Real HTTP headers arrive immediately; the test controls when the body bytes arrive. */
    private class DelayedBody(body: String) : AutoCloseable {
        val bodyReadStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
        private val bytes = body.toByteArray()
        private val server = loopbackServer {
            MockResponse
                .Builder()
                .addHeader("Content-Type", "application/json")
                .body(object : MockResponseBody {
                    override val contentLength = bytes.size.toLong()

                    override fun writeTo(sink: BufferedSink) {
                        sink.write(bytes, 0, 1).flush()
                        check(releaseBody.await(10, TimeUnit.SECONDS)) {
                            "Test did not release response body"
                        }
                        sink.write(bytes, 1, bytes.size - 1).flush()
                    }
                })
                .build()
        }
        private val client = OkHttpClient
            .Builder()
            .eventListener(object : EventListener() {
                override fun responseBodyStart(call: Call) {
                    bodyReadStarted.complete(Unit)
                }

                override fun canceled(call: Call) {
                    callCancelled.complete(Unit)
                }
            })
            .build()
        val credentials = AccountCredentials("${server.origin}/", "test", "password", "test")
        val source =
            NextcloudCapabilitiesClient(
                NextcloudTransport(Json { ignoreUnknownKeys = true }, client),
            ) { credentials }

        override fun close() {
            releaseBody.countDown()
            server.close()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
