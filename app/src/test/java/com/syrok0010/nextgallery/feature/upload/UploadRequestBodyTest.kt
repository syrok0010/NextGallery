package com.syrok0010.nextgallery.feature.upload

import java.io.IOException
import java.io.InputStream
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UploadRequestBodyTest {
    @Test fun `streams identical bytes and counts them from zero on another write`() {
        val bytes = ByteArray(1024 * 1024) { it.toByte() }
        val sent = mutableListOf<Long>()
        val body =
            UploadRequestBody(
                bytes.size.toLong(),
                null,
                { bytes.inputStream() },
                onSent = sent::add,
            )
        repeat(2) {
            sent.clear()
            val output = Buffer()
            body.writeTo(output)
            assertArrayEquals(bytes, output.readByteArray())
            assertEquals(0L, sent.first())
            assertEquals(bytes.size.toLong(), sent.last())
            assertEquals(sent.sorted(), sent)
        }
    }

    @Test fun `a part streams its own range`() {
        val sent = mutableListOf<Long>()
        val output = Buffer()
        UploadRequestBody(
            4L,
            null,
            { "0123456789".byteInputStream() },
            offset = 4L,
            onSent = sent::add,
        ).writeTo(output)
        assertEquals("4567", output.readUtf8())
        assertEquals(listOf(0L, 4L), sent)
    }

    @Test fun `unknown length streams to the end`() {
        val output = Buffer()
        UploadRequestBody(-1L, null, { byteArrayOf(1, 2, 3).inputStream() }) {}.writeTo(output)
        assertArrayEquals(byteArrayOf(1, 2, 3), output.readByteArray())
    }

    @Test fun `a file shorter than the offset fails`() {
        assertThrows(IOException::class.java) {
            UploadRequestBody(
                4L,
                null,
                { "012".byteInputStream() },
                offset = 4L,
            ) {}.writeTo(Buffer())
        }
    }

    @Test fun `read failure closes input`() {
        var closed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("lost source")
            override fun close() {
                closed = true
            }
        }
        assertThrows(IOException::class.java) {
            UploadRequestBody(100L, null, { input }) {}.writeTo(Buffer())
        }
        assertTrue(closed)
    }

    @Test fun `percent leaves 100 for the response and needs a known size`() {
        assertEquals(0, sentPercent(0L, 10L))
        assertEquals(40, sentPercent(4L, 10L))
        assertEquals(99, sentPercent(10L, 10L))
        assertEquals(0, sentPercent(0L, 0L))
        assertNull(sentPercent(5L, -1L))
        assertEquals(50, sentPercent(Long.MAX_VALUE / 2, Long.MAX_VALUE))
    }
}
