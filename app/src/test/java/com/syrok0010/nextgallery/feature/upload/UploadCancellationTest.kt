package com.syrok0010.nextgallery.feature.upload

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UploadCancellationTest {
    @Test fun `cancellation before call registration prevents HTTP request`() {
        val item = batch(1).items.single()
        item.cancel()
        val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("http://127.0.0.1:1/").build())
        assertThrows(IOException::class.java) { item.execute(call) }
        assertTrue(call.isCanceled())
        assertFalse(item.complete(UploadPhase.Uploaded))
        assertEquals(UploadPhase.Cancelled, item.state.value.phase)
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
            val call = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url("http://127.0.0.1:${server.address.port}/").build())
            val reader = async(Dispatchers.IO) { runCatching { item.execute(call).use { it.body!!.bytes() } } }
            assertTrue(bodyStarted.await(5, TimeUnit.SECONDS))
            item.cancel()
            assertTrue(withTimeout(2_000) { reader.await() }.isFailure)
            assertTrue(call.isCanceled())
            assertEquals(UploadPhase.Cancelled, item.state.value.phase)
        } finally {
            release.countDown()
            server.stop(0)
        }
    }

    private fun batch(size: Int) = UploadBatch((0 until size).map { UploadSelection("content://media/$it", "$it.jpg", "image/jpeg") })
}
