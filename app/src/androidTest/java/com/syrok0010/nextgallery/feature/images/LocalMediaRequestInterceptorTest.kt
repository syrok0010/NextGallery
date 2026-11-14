package com.syrok0010.nextgallery.feature.images

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil3.ImageLoader
import coil3.Uri
import coil3.asImage
import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.decode.ImageSource
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.ImageRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMediaRequestInterceptorTest {
    @Test
    fun cancelledBlockingOpensKeepTheirPermitsAndQueuedRequestsNeverOpen() =
        checkCancellation(blockInDecoder = false)

    @Test
    fun permitsCoverDecodingAsWellAsOpening() = checkCancellation(blockInDecoder = true)

    private fun checkCancellation(blockInDecoder: Boolean) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val permits = LocalMediaRequestInterceptor.PERMITS
        val entered = CountDownLatch(permits)
        val release = CountDownLatch(1)
        val opens = AtomicInteger()
        val blocks = AtomicInteger()
        fun block() {
            blocks.incrementAndGet()
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Test did not release blocking IO" }
        }
        val loader = ImageLoader.Builder(context)
            .components {
                add(LocalMediaRequestInterceptor())
                add(Fetcher.Factory<Uri> { _, options, _ ->
                    Fetcher {
                        opens.incrementAndGet()
                        if (!blockInDecoder) block()
                        SourceFetchResult(
                            source = ImageSource(Buffer().writeUtf8("fixture"), options.fileSystem),
                            mimeType = "image/test",
                            dataSource = DataSource.DISK,
                        )
                    }
                })
                add(Decoder.Factory { _, _, _ ->
                    Decoder {
                        if (blockInDecoder) block()
                        DecodeResult(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).asImage(), false)
                    }
                })
            }
            .build()
        fun request(id: Int) = ImageRequest.Builder(context)
            .data("content://media/external/images/media/$id")
            .size(1, 1)
            .build()
        val first = (1..permits).map { async(Dispatchers.Default) { loader.execute(request(it)) } }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            first.forEach { it.cancel() }
            val queued = (permits + 1..permits + 30).map { async(Dispatchers.Default) { loader.execute(request(it)) } }
            try {
                delay(400)
                assertEquals("Cancelled blocking work must still occupy every permit", permits, opens.get())
                assertEquals(permits, blocks.get())
                queued.forEach { it.cancel() }
            } finally {
                queued.forEach { it.cancel() }
                release.countDown()
                withTimeout(5_000) { queued.forEach { it.join() } }
            }
            withTimeout(5_000) { first.forEach { it.join() } }
            assertEquals("Cancelled queued requests must never open a file", permits, opens.get())
            withTimeout(5_000) { loader.execute(request(permits + 31)) }
            assertEquals("Permits must be reusable after cleanup", permits + 1, opens.get())
        } finally {
            release.countDown()
            first.forEach { it.cancel() }
            loader.shutdown()
        }
    }
}
