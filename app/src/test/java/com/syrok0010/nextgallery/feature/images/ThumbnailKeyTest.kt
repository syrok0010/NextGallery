package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.session.AccountCredentials
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
            thumbnailKey(
                accountScope = "https://cloud.example.com|other-user",
            ).coilMemoryCacheKey(),
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
    fun `thumbnail request keys by account file and etag`() {
        fun key(
            serverUrl: String = "https://cloud.example.com",
            userId: String = "user",
            fileId: Long = 42,
            etag: String = "etag",
        ) = thumbnailRequest(
            credentials = AccountCredentials(
                serverUrl,
                loginName = userId,
                appPassword = "secret",
                userId = userId,
            ),
            fileId = fileId,
            etag = etag,
        ).key.coilMemoryCacheKey()

        assertEquals(key(), key(serverUrl = "https://cloud.example.com/"))
        assertNotEquals(key(), key(userId = "other"))
        assertNotEquals(key(), key(serverUrl = "https://other.example.com"))
        assertNotEquals(key(), key(fileId = 43))
        assertNotEquals(key(), key(etag = "new-etag"))
    }

    @Test
    fun `local cache key changes when MediaStore copy is modified`() {
        val original = MediaAssetRef.LocalContent(
            contentUri = "content://media/external/images/media/42",
            modifiedAtEpochSeconds = 1_700_000_000,
            sizeBytes = 1,
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
    ): ThumbnailKey =
        ThumbnailKey(
            accountScope = accountScope,
            fileId = 42,
            width = width,
            height = height,
            etag = etag,
        )
}
