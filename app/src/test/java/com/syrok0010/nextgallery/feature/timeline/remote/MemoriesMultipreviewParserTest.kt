package com.syrok0010.nextgallery.data.memories

import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesMultipreviewParserTest {
    private val json = Json {
        ignoreUnknownKeys = true
    }

    @Test
    fun `parse multiple preview blocks`() {
        val firstImage = byteArrayOf(1, 2, 3)
        val secondImage = byteArrayOf(4, 5)
        val response = previewBlock(reqid = 7, type = "image/jpeg", image = firstImage) +
            previewBlock(reqid = 8, type = "image/png", image = secondImage)

        val previews = parseMemoriesMultipreviewResponse(response, json)

        assertEquals(2, previews.size)
        assertEquals(7, previews[0].requestId)
        assertEquals("image/jpeg", previews[0].mimeType)
        assertArrayEquals(firstImage, previews[0].bytes)
        assertEquals(8, previews[1].requestId)
        assertEquals("image/png", previews[1].mimeType)
        assertArrayEquals(secondImage, previews[1].bytes)
    }

    @Test
    fun `parse empty response`() {
        val previews = parseMemoriesMultipreviewResponse(ByteArray(0), json)

        assertEquals(emptyList<ThumbnailPreview>(), previews)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject truncated header`() {
        parseMemoriesMultipreviewResponse(byteArrayOf(10, 1, 2), json)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject truncated image`() {
        val response = previewBlock(reqid = 7, type = "image/jpeg", image = byteArrayOf(1, 2, 3))
            .dropLast(1)
            .toByteArray()

        parseMemoriesMultipreviewResponse(response, json)
    }

    private fun previewBlock(
        reqid: Int,
        type: String,
        image: ByteArray,
    ): ByteArray {
        val header = """{"reqid":$reqid,"len":${image.size},"type":"$type"}""".encodeToByteArray()
        require(header.size <= 255)

        return byteArrayOf(header.size.toByte()) + header + image
    }
}
