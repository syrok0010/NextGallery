package com.syrok0010.nextgallery.feature.upload

import com.sun.net.httpserver.HttpServer
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

/** Cancelling a job cancels its coroutine; the DAV calls must give up the connection at once. */
class UploadCancellationTest {
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
                val dav = dav(server)
                val worker = launch(Dispatchers.IO) {
                    placeUpload(
                        dav,
                        PATH,
                        SELECTION,
                        5L,
                        { dav.createFile(it, "bytes".toRequestBody()) },
                    ) { "bytes".byteInputStream() }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { worker.cancelAndJoin() }
                assertTrue(worker.isCancelled)
            } finally {
                release.countDown()
                server.stop(0)
            }
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
                val dav = dav(server)
                val reader =
                    launch(Dispatchers.IO) { dav.readFile(PATH, etag = null) { it.readBytes() } }
                assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
                withTimeout(2_000) { reader.cancelAndJoin() }
                assertTrue(reader.isCancelled)
            } finally {
                release.countDown()
                server.stop(0)
            }
        }

    private fun dav(server: HttpServer): FilesDavClient {
        val credentials =
            AccountCredentials("http://127.0.0.1:${server.address.port}", "test", "test", "test")
        return FilesDavClient(NextcloudTransport(Json)) { credentials }
    }

    private companion object {
        val PATH = DavFilePath.of("test", "/Photos", "photo.jpg")
        val SELECTION =
            UploadSelection(
                MediaAssetRef.LocalContent("content://media/1", 1, 1),
                "photo.jpg",
                "image/jpeg",
            )
    }
}
