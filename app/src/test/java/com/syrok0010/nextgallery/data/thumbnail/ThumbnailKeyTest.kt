package com.syrok0010.nextgallery.data.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailKeyTest {
    @Test
    fun `coil cache key is stable for the same thumbnail identity`() {
        val first = thumbnailKey()
        val second = thumbnailKey()

        assertEquals(first.coilMemoryCacheKey(), second.coilMemoryCacheKey())
    }

    @Test
    fun `coil cache key separates accounts etags and sizes`() {
        val original = thumbnailKey()

        assertNotEquals(
            original.coilMemoryCacheKey(),
            thumbnailKey(accountScope = "https://cloud.example.com|other-user").coilMemoryCacheKey(),
        )
        assertNotEquals(
            original.coilMemoryCacheKey(),
            thumbnailKey(etag = "new-etag").coilMemoryCacheKey(),
        )
        assertNotEquals(
            original.coilMemoryCacheKey(),
            thumbnailKey(width = 256, height = 256).coilMemoryCacheKey(),
        )
    }

    private fun thumbnailKey(
        accountScope: String = "https://cloud.example.com|user",
        width: Int = 512,
        height: Int = 512,
        etag: String? = "etag",
    ): ThumbnailKey {
        return ThumbnailKey(
            accountScope = accountScope,
            fileId = 42,
            width = width,
            height = height,
            etag = etag,
        )
    }
}
