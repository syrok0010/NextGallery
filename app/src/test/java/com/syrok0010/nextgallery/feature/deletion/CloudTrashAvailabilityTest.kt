package com.syrok0010.nextgallery.feature.deletion

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.albums.MemoriesAlbumWriterTest.Fixture
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Credentials
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudTrashAvailabilityTest {
    @Test fun `only an explicit boolean in successful OCS capabilities confirms trash`() {
        assertEquals(true, cloudTrashAvailable(capabilities("true")))
        assertEquals(false, cloudTrashAvailable(capabilities("false")))
        assertEquals(true, cloudTrashAvailable(capabilities("true", 100)))
        listOf("null", "1", "0", "\"true\"", "{}", "[]").forEach { value ->
            assertNull(cloudTrashAvailable(capabilities(value)))
        }
        assertNull(cloudTrashAvailable(capabilities("true", 997)))
        // With files_trashbin disabled the stand omits undelete instead of sending false.
        assertNull(
            cloudTrashAvailable(
                """{"ocs":{"meta":{"statuscode":200},"data":{"capabilities":{"files":{"versioning":true}}}}}""",
            ),
        )
        listOf("{}", "[]", "null", "not JSON").forEach {
            assertNull(cloudTrashAvailable(it))
        }
    }

    @Test fun `capabilities request is authenticated and HTTP errors cannot confirm trash`() =
        runBlocking {
            var status = 200
            Fixture { status to capabilities("true") }.use { fixture ->
                val source = NextcloudTrashBin(fixture.writerTransport)
                assertEquals(true, source.available(fixture.credentials))
                val request = fixture.requests.single()
                assertEquals("GET", request.method)
                assertEquals("/ocs/v2.php/cloud/capabilities?format=json", request.target)
                assertEquals("true", request.headers["ocs-apirequest"])
                assertEquals(
                    Credentials.basic("fixture", "password"),
                    request.headers["authorization"],
                )
                status = 503
                assertNull(source.available(fixture.credentials))
            }
        }

    @Test fun `delayed response body leaves caller dispatcher available`() =
        runBlocking {
            DelayedBody(capabilities("true")).use { fixture ->
                Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { caller ->
                    val result = async(caller) { fixture.source.available(fixture.credentials) }
                    try {
                        withTimeout(5_000) { fixture.bodyReadStarted.await() }
                        // A second task must run while the capabilities body is still withheld.
                        val heartbeat = async(caller) { true }
                        assertTrue(withTimeout(1_000) { heartbeat.await() })
                        fixture.releaseBody.countDown()
                        assertEquals(true, withTimeout(5_000) { result.await() })
                    } finally {
                        fixture.releaseBody.countDown()
                        result.cancelAndJoin()
                    }
                }
            }
        }

    @Test fun `cancelling while reading delayed body cancels HTTP call promptly`() =
        runBlocking {
            DelayedBody(capabilities("true")).use { fixture ->
                val result = async { fixture.source.available(fixture.credentials) }
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

    @Test fun `login loads trash and refresh drops an old promise before failure`() =
        runTest {
            val sessions = sessions()
            val gate = CompletableDeferred<Boolean?>()
            var calls = 0
            val repository = CloudTrashAvailability(sessions, backgroundScope) {
                when (++calls) {
                    1 -> true
                    2 -> gate.await()
                    else -> throw IOException("offline")
                }
            }
            runCurrent()
            assertNull(repository.available.value)
            sessions.signIn(account)
            runCurrent()
            assertEquals(true, repository.available.value)
            repository.refresh()
            assertNull(repository.available.value)
            runCurrent()
            gate.complete(false)
            runCurrent()
            assertEquals(false, repository.available.value)
            repository.refresh()
            runCurrent()
            assertNull(repository.available.value)
            assertEquals(3, calls)
        }

    @Test fun `account replacement cancels old request and logout forgets capabilities`() =
        runTest {
            val sessions = sessions()
            sessions.signIn(account)
            var cancelled = false
            val repository = CloudTrashAvailability(sessions, backgroundScope) { credentials ->
                if (credentials == account) {
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                } else {
                    false
                }
            }
            runCurrent()
            sessions.signIn(account.copy(userId = "other", loginName = "other"))
            runCurrent()
            assertTrue(cancelled)
            assertEquals(false, repository.available.value)
            sessions.signOut()
            runCurrent()
            assertNull(repository.available.value)
        }

    private fun sessions() =
        SessionStore(object : CredentialsStore {
            override fun load(): AccountCredentials? = null
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })

    private val account = AccountCredentials("https://cloud.example/", "test", "password", "test")

    private fun capabilities(undelete: String, status: Int = 200) =
        """{"ocs":{"meta":{"statuscode":$status},"data":{"capabilities":{"files":{"undelete":$undelete}}}}}"""

    /** Real HTTP headers arrive immediately; the test controls when socket body bytes arrive. */
    private class DelayedBody(body: String) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        private val worker = Executors.newSingleThreadExecutor()
        val bodyReadStarted = CompletableDeferred<Unit>()
        val callCancelled = CompletableDeferred<Unit>()
        val releaseBody = CountDownLatch(1)
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
        val source = NextcloudTrashBin(NextcloudTransport(Json, client))
        val credentials =
            AccountCredentials("http://127.0.0.1:${server.localPort}/", "test", "password", "test")

        init {
            worker.submit {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    val bytes = body.toByteArray()
                    val output = socket.getOutputStream()
                    output.write(
                        (
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                            ).toByteArray(),
                    )
                    output.write(bytes, 0, 1)
                    output.flush()
                    check(
                        releaseBody.await(10, TimeUnit.SECONDS),
                    ) { "Test did not release response body" }
                    output.write(bytes, 1, bytes.size - 1)
                    output.flush()
                }
            }
        }

        override fun close() {
            releaseBody.countDown()
            server.close()
            worker.shutdownNow()
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }
}
