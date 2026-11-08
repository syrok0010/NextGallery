package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.sun.net.httpserver.HttpServer
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
    @Test fun `cancelled pending files are never transferred and successful response race cannot add to album`() = runBlocking {
        val batch = batch(3)
        val transferred = mutableListOf<String>()
        val accepted = mutableListOf<String>()
        val processed = mutableListOf<String>()
        batch.items[1].cancel()
        runUploadFiles(batch, transfer = { item ->
            transferred += item.selection.displayName
            if (item === batch.items[0]) batch.cancel() // Response arrived but not yet accepted.
            uploaded(item)
        }, accepted = { accepted += it.selection.displayName }, failureReason = { "failure" }, processed = { processed += it.displayName })
        assertEquals(listOf("0.jpg"), transferred)
        assertTrue(accepted.isEmpty())
        assertEquals(3, processed.size)
        assertTrue(batch.items.all { it.state.value.phase == UploadPhase.Cancelled })
    }

    @Test fun `accepted file survives batch cancellation during follow-up and remaining files finish`() = runBlocking {
        val batch = batch(3)
        val accepted = mutableListOf<String>()
        val processed = mutableListOf<String>()
        runUploadFiles(batch, transfer = { uploaded(it) }, accepted = {
            accepted += it.selection.displayName
            batch.cancel()
        }, failureReason = { "failure" }, processed = { processed += it.displayName })
        assertEquals(listOf("0.jpg"), accepted)
        assertEquals(listOf(UploadPhase.Uploaded, UploadPhase.Cancelled, UploadPhase.Cancelled), phases(batch))
        assertEquals(3, processed.size)
        assertFalse(batch.items[0].progress(40))
    }

    @Test fun `partial results distinguish existing conflict failure and files not attempted`() = runBlocking {
        val batch = batch(5)
        val accepted = mutableListOf<String>()
        val transferred = mutableListOf<String>()
        runUploadFiles(batch, transfer = { item ->
            transferred += item.selection.displayName
            when (item) {
                batch.items[0] -> uploaded(item)
                batch.items[1] -> uploaded(item).copy(created = false)
                batch.items[2] -> null
                else -> throw IOException("offline")
            }
        }, accepted = { accepted += it.selection.displayName }, failureReason = { it.message!! }, processed = {})
        assertEquals(listOf(UploadPhase.Uploaded, UploadPhase.Existing, UploadPhase.NameConflict, UploadPhase.Failed, UploadPhase.Failed), phases(batch))
        assertEquals(listOf("0.jpg", "1.jpg"), accepted)
        assertEquals(4, transferred.size)
        assertEquals("offline", batch.items[4].state.value.failure)
    }

    @Test fun `cancellation closes actual in-flight PUT and lets the next file complete`() = runBlocking {
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
            val batch = batch(2)
            val accepted = mutableListOf<String>()
            val dav = dav(server)
            val worker = async(Dispatchers.IO) {
                runUploadFiles(batch, transfer = { item ->
                    if (item === batch.items[0]) {
                        item.run { placeUpload(dav, PATH, item.selection, "bytes".toRequestBody()) { "bytes".byteInputStream() } }
                    } else {
                        uploaded(item)
                    }
                }, accepted = { accepted += it.selection.displayName }, failureReason = { "network" }, processed = {})
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            batch.items[0].cancel()
            withTimeout(2_000) { worker.await() }
            assertEquals(listOf(UploadPhase.Cancelled, UploadPhase.Uploaded), phases(batch))
            assertEquals(listOf("1.jpg"), accepted)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    @Test fun `cancellation before the work starts prevents any request`() = runBlocking {
        val item = batch(1).items.single()
        item.cancel()
        var started = false
        val error = runCatching { item.run { started = true } }.exceptionOrNull()
        assertTrue(error is IOException)
        assertFalse(started)
        assertFalse(item.complete(UploadPhase.Uploaded))
        assertEquals(UploadPhase.Cancelled, item.state.value.phase)
    }

    @Test fun `all cancelled batch finishes follow-up and supplies every missed file`() = runBlocking {
        val missed = mutableListOf<List<UploadSelection>>()
        val resolved = CompletableDeferred<Unit>()
        val followUp = object : UploadFollowUp {
            override suspend fun uploaded(credentials: AccountCredentials, file: UploadedFile) { error("Cancelled file reached album") }
            override suspend fun finished(files: List<UploadSelection>): List<com.syrok0010.nextgallery.core.ui.UiText> {
                missed += files
                resolved.complete(Unit)
                return emptyList()
            }
        }
        val files = batch(3).files
        val batch = UploadBatch(files, listOf(followUp))
        batch.cancel()
        runUploadFiles(batch, transfer = { error("Cancelled file started") }, accepted = {}, failureReason = { "failure" }, processed = {})
        finishUploadBatch(batch)
        withTimeout(1_000) { resolved.await() }
        assertEquals(listOf(files), missed)
    }

    @Test fun `cancellation racing socket failure does not stop remaining files`() = runBlocking {
        val batch = batch(2)
        val accepted = mutableListOf<String>()
        runUploadFiles(
            batch,
            transfer = { item ->
                if (item === batch.items[0]) throw IOException("closed") else uploaded(item)
            },
            accepted = { accepted += it.selection.displayName },
            failureReason = { batch.items[0].cancel(); "network" },
            processed = {},
        )
        assertEquals(listOf(UploadPhase.Cancelled, UploadPhase.Uploaded), phases(batch))
        assertEquals(listOf("1.jpg"), accepted)
    }

    @Test fun `cancellation closes a DAV response whose headers arrived but body is stalled`() = runBlocking {
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
            val item = batch(1).items.single()
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

    private fun batch(size: Int) = UploadBatch((0 until size).map { UploadSelection(MediaAssetRef.LocalContent("content://media/$it", 1, 1), "$it.jpg", "image/jpeg") })
    private fun uploaded(item: UploadItem) = UploadedFile(item.selection, PATH, true)
    private fun dav(server: HttpServer): FilesDavClient {
        val credentials = AccountCredentials("http://127.0.0.1:${server.address.port}", "test", "test", "test")
        return FilesDavClient(NextcloudTransport(Json)) { credentials }
    }
    private fun phases(batch: UploadBatch) = batch.items.map { it.state.value.phase }

    private companion object {
        val PATH = DavFilePath.of("test", "/Photos", "photo.jpg")
    }
}
