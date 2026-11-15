package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.ThumbnailCacheEntity
import com.syrok0010.nextgallery.core.network.memories.MemoriesPreviewDto

internal class RemoteImageCache(
    database: NextGalleryDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
) {
    private val thumbnailDao = database.thumbnailCacheDao()
    suspend fun loadThumbnailKeys(keys: List<ThumbnailKey>): List<ThumbnailKey> {
        if (keys.isEmpty()) return emptyList()
        val first = keys.first()
        val keysByFileId = keys.associateBy { it.fileId }
        val rows = thumbnailDao.rows(keysByFileId.keys.toList(), first.width, first.height)
        val staleRows = mutableListOf<ThumbnailCacheEntity>()
        val cachedKeys = rows.mapNotNull { row ->
            val thumbnailKey = keysByFileId.getValue(row.fileId)
            val expectedCacheKey = thumbnailFileStore.cacheKey(thumbnailKey)
            if (row.cacheKey != expectedCacheKey) {
                staleRows += row
                return@mapNotNull null
            }

            if (!thumbnailFileStore.exists(row.relativePath)) {
                staleRows += row
                null
            } else {
                thumbnailKey
            }
        }

        if (staleRows.isNotEmpty()) {
            thumbnailDao.delete(staleRows.map { it.fileId }, first.width, first.height)
            thumbnailFileStore.delete(staleRows.map { it.relativePath })
        }

        return cachedKeys
    }

    suspend fun saveThumbnails(previews: List<MemoriesPreviewDto>, keys: List<ThumbnailKey>) {
        if (previews.isEmpty()) {
            return
        }

        val keysByFileId = keys.associateBy { it.fileId }
        val now = System.currentTimeMillis()
        val rows = previews.map { preview ->
            val thumbnailKey = keysByFileId.getValue(preview.fileId)
            val storedFile = thumbnailFileStore.save(
                key = thumbnailKey,
                bytes = preview.bytes,
            )
            ThumbnailCacheEntity(
                fileId = preview.fileId,
                width = thumbnailKey.width,
                height = thumbnailKey.height,
                mimeType = preview.type,
                cacheKey = storedFile.cacheKey,
                relativePath = storedFile.relativePath,
                cachedAtEpochMillis = now,
            )
        }

        thumbnailDao.upsert(rows)
    }
}
