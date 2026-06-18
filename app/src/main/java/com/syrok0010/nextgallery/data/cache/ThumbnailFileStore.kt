package com.syrok0010.nextgallery.data.cache

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailFileStore(context: Context) {
    private val root = File(context.cacheDir, THUMBNAIL_CACHE_DIRECTORY)

    suspend fun load(relativePath: String): ByteArray? {
        return withContext(Dispatchers.IO) {
            val file = File(root, relativePath)
            if (file.isFile) {
                file.readBytes()
            } else {
                null
            }
        }
    }

    suspend fun save(
        fileId: Long,
        width: Int,
        height: Int,
        etag: String?,
        bytes: ByteArray,
    ): StoredThumbnailFile {
        return withContext(Dispatchers.IO) {
            root.mkdirs()
            val cacheKey = cacheKey(fileId, width, height, etag)
            File(root, cacheKey).writeBytes(bytes)
            StoredThumbnailFile(
                cacheKey = cacheKey,
                relativePath = cacheKey,
            )
        }
    }

    suspend fun delete(relativePaths: Collection<String>) {
        if (relativePaths.isEmpty()) {
            return
        }

        withContext(Dispatchers.IO) {
            relativePaths.forEach { relativePath ->
                File(root, relativePath).delete()
            }
        }
    }

    suspend fun clear() {
        withContext(Dispatchers.IO) {
            root.deleteRecursively()
        }
    }

    fun cacheKey(
        fileId: Long,
        width: Int,
        height: Int,
        etag: String?,
    ): String {
        val etagPart = etag
            ?.takeIf { it.isNotBlank() }
            ?.map { character ->
                if (character.isLetterOrDigit() || character == '-' || character == '_' || character == '.') {
                    character
                } else {
                    '_'
                }
            }
            ?.joinToString(separator = "")
            ?: NO_ETAG

        return "$fileId-${width}x$height-$etagPart.bin"
    }

    private companion object {
        const val THUMBNAIL_CACHE_DIRECTORY = "thumbnail-cache"
        const val NO_ETAG = "noetag"
    }
}

data class StoredThumbnailFile(
    val cacheKey: String,
    val relativePath: String,
)
