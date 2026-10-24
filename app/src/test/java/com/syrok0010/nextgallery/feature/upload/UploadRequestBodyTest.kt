package com.syrok0010.nextgallery.feature.upload

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class UploadRequestBodyTest {
    @Test fun `streams identical bytes and resets progress on another write`() {
        val bytes = ByteArray(1024 * 1024) { it.toByte() }
        val progress = mutableListOf<Int?>()
        var time = 0L
        val body = UploadRequestBody(
            bytes.size.toLong(),
            null,
            { ByteArrayInputStream(bytes) },
            progress::add,
            monotonicMillis = { time.also { time += 250L } },
        )
        repeat(2) {
            progress.clear()
            val output = Buffer()
            body.writeTo(output)
            assertArrayEquals(bytes, output.readByteArray())
            assertEquals(0, progress.first())
            assertTrue(progress.any { it != null && it in 1..98 })
            assertEquals(99, progress.last())
            assertEquals(progress.distinct().sortedBy { it }, progress)
        }
    }

    @Test fun `fast writes do not flood notifications`() {
        val progress = mutableListOf<Int?>()
        val bytes = ByteArray(1024 * 1024)
        UploadRequestBody(
            bytes.size.toLong(),
            null,
            { bytes.inputStream() },
            progress::add,
            monotonicMillis = { 0L },
        ).writeTo(Buffer())
        assertEquals(listOf(0), progress)
    }

    @Test fun `unknown and empty lengths do not invent a percentage`() {
        for (length in listOf(-1L, 0L)) {
            val progress = mutableListOf<Int?>()
            val bytes = if (length < 0) byteArrayOf(1, 2, 3) else byteArrayOf()
            val output = Buffer()
            UploadRequestBody(length, null, { bytes.inputStream() }, progress::add).writeTo(output)
            assertEquals(listOf(if (length < 0) null else 0), progress)
            assertArrayEquals(bytes, output.readByteArray())
        }
    }

    @Test fun `large lengths do not overflow progress arithmetic`() {
        val progress = mutableListOf<Int?>()
        var time = 0L
        UploadRequestBody(
            Long.MAX_VALUE,
            null,
            { ByteArray(65536).inputStream() },
            progress::add,
            monotonicMillis = { time.also { time += 250L } },
        ).writeTo(Buffer())
        assertEquals(listOf(0), progress)
    }

    @Test fun `read failure closes input and never reports completion`() {
        var closed = false
        val progress = mutableListOf<Int?>()
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("lost source")
            override fun close() {
                closed = true
            }
        }
        assertThrows(IOException::class.java) {
            UploadRequestBody(100L, null, { input }, progress::add).writeTo(Buffer())
        }
        assertTrue(closed)
        assertFalse(progress.contains(100))
    }
}
