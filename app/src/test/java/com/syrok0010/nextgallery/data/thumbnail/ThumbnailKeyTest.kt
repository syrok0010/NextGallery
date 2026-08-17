package com.syrok0010.nextgallery.data.thumbnail

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
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

    @Test
    fun `thumbnail request creates its cache identity immediately`() {
        val request = thumbnailRequest(
            credentials = AccountCredentials(
                serverUrl = "https://cloud.example.com/",
                loginName = "user",
                appPassword = "secret",
            ),
            fileId = 42,
            etag = "etag",
        )

        assertEquals("https://cloud.example.com|user", request.key.accountScope)
        assertEquals(42L, request.key.fileId)
        assertEquals(512, request.key.width)
        assertEquals("etag", request.key.etag)
    }

    @Test
    fun `local cache key changes when MediaStore copy is modified`() {
        val original = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
        )
        val modified = original.copy(modifiedAtEpochSeconds = 1_700_000_001)

        assertEquals(original.coilCacheKey(), original.copy().coilCacheKey())
        assertNotEquals(original.coilCacheKey(), modified.coilCacheKey())
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
