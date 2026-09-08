package com.syrok0010.nextgallery.data.cache

import android.content.Context
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ThumbnailFileStore(context: Context) {
    private val root = File(context.cacheDir, THUMBNAIL_CACHE_DIRECTORY)

    suspend fun exists(relativePath: String): Boolean {
        return withContext(Dispatchers.IO) {
            File(root, relativePath).isFile
        }
    }

    suspend fun save(
        key: ThumbnailKey,
        bytes: ByteArray,
    ): StoredThumbnailFile {
        return withContext(Dispatchers.IO) {
            root.mkdirs()
            val cacheKey = cacheKey(key)
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

    internal fun cacheKey(key: ThumbnailKey): String {
        val etagPart = key.etag
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

        return "${key.accountScope.sha256()}-${key.fileId}-${key.width}x${key.height}-$etagPart.bin"
    }

    internal fun fileFor(key: ThumbnailKey): File {
        return File(root, cacheKey(key))
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray())
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
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
