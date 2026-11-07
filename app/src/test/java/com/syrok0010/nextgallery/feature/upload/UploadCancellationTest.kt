package com.syrok0010.nextgallery.feature.upload

import com.sun.net.httpserver.HttpServer
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class UploadCancellationTest {
    @Test fun `cancellation before the work starts prevents any request`() =
        runBlocking {
            val item = item()
            item.cancel()
            var started = false
            val error = runCatching { item.run { started = true } }.exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(started)
            assertFalse(item.complete(UploadPhase.Uploaded))
            assertEquals(UploadPhase.Cancelled, item.state.value.phase)
        }

    @Test fun `cancellation closes an in-flight PUT`() =
        runBlocking {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    it.requestBody.readBytes()
                    if (it.requestMethod == "PROPFIND") {
                        it.sendResponseHeaders(404, -1)
                    } else {
                        entered.countDown()
                        release.await(5, TimeUnit.SECONDS)
                        runCatching { it.sendResponseHeaders(201, -1) }
                    }
                }
            }
            server.start()
            try {
                val item = item()
                val dav = dav(server)
                val worker = async(Dispatchers.IO) {
                    runCatching {
                        item.run {
                            placeUpload(
                                dav,
                                PATH,
                                item.selection,
                                "bytes".toRequestBody(),
                            ) {
                                "bytes".byteInputStream()
                            }
                        }
                    }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                item.cancel()
                assertTrue(withTimeout(2_000) { worker.await() }.exceptionOrNull() is IOException)
                assertEquals(UploadPhase.Cancelled, item.state.value.phase)
            } finally {
                release.countDown()
                server.stop(0)
            }
        }

    @Test fun `stopping the executor aborts the work without deciding the outcome`() =
        runBlocking {
            val item = item()
            val started = CompletableDeferred<Unit>()
            val worker = async {
                runCatching {
                    item.run {
                        started.complete(Unit)
                        awaitCancellation()
                    }
                }
            }
            started.await()
            item.stopCall()
            assertTrue(withTimeout(2_000) { worker.await() }.exceptionOrNull() is IOException)
            assertEquals(UploadPhase.Waiting, item.state.value.phase)
        }

    @Test fun `cancellation closes a DAV response whose headers arrived but body is stalled`() =
        runBlocking {
            val bodyStarted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/") { exchange ->
                exchange.use {
                    it.sendResponseHeaders(200, 1024)
                    it.responseBody.write(1)
                    it.responseBody.flush()
                    bodyStarted.countDown()
                    release.await(5, TimeUnit.SECONDS)
                }
            }
            server.start()
            try {
                val item = item()
                val dav = dav(server)
                val reader = async(Dispatchers.IO) {
                    runCatching { item.run { dav.readFile(PATH, etag = null) { it.readBytes() } } }
                }
                assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
                item.cancel()
                assertTrue(withTimeout(2_000) { reader.await() }.exceptionOrNull() is IOException)
                assertEquals(UploadPhase.Cancelled, item.state.value.phase)
            } finally {
                release.countDown()
                server.stop(0)
            }
        }

    private fun item() =
        UploadItem(
            UploadSelection(
                MediaAssetRef.LocalContent("content://media/1", 1, 1),
                "photo.jpg",
                "image/jpeg",
            ),
        )
    private fun serverUrl(server: HttpServer) = "http://127.0.0.1:${server.address.port}"
    private fun dav(server: HttpServer): FilesDavClient {
        val credentials = AccountCredentials(serverUrl(server), "test", "test", "test")
        return FilesDavClient(NextcloudTransport(Json)) { credentials }
    }

    private companion object {
        val PATH = DavFilePath.of("test", "/Photos", "photo.jpg")
    }
}
