package com.syrok0010.nextgallery.core.network.memories

import java.io.EOFException
import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoriesMultipreviewStreamTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `read preview blocks one by one`() {
        val firstImage = byteArrayOf(1, 2, 3)
        val secondImage = byteArrayOf(4, 5)
        val source = Buffer()
            .write(previewBlock(reqid = 7, type = "image/jpeg", image = firstImage))
            .write(previewBlock(reqid = 8, type = "image/png", image = secondImage))

        val first = readMemoriesPreview(source, json)!!
        assertEquals(7, first.reqid)
        assertEquals("image/jpeg", first.type)
        assertArrayEquals(firstImage, first.bytes)
        val second = readMemoriesPreview(source, json)!!
        assertEquals(8, second.reqid)
        assertEquals("image/png", second.type)
        assertArrayEquals(secondImage, second.bytes)
        assertNull(readMemoriesPreview(source, json))
    }

    @Test
    fun `empty response has no previews`() {
        assertNull(readMemoriesPreview(Buffer(), json))
    }

    @Test(expected = EOFException::class)
    fun `reject truncated header`() {
        readMemoriesPreview(Buffer().write(byteArrayOf(10, 1, 2)), json)
    }

    @Test(expected = EOFException::class)
    fun `reject truncated image`() {
        val block = previewBlock(reqid = 7, type = "image/jpeg", image = byteArrayOf(1, 2, 3))
        readMemoriesPreview(Buffer().write(block.copyOf(block.size - 1)), json)
    }

    private fun previewBlock(reqid: Int, type: String, image: ByteArray): ByteArray {
        val header = """{"reqid":$reqid,"len":${image.size},"type":"$type"}""".encodeToByteArray()
        require(header.size <= 255)

        return byteArrayOf(header.size.toByte()) + header + image
    }
}
