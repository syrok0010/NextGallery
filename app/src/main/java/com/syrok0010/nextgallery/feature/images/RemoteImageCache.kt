package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.ThumbnailCacheEntity

internal class RemoteImageCache(
    database: NextGalleryDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
) {
    private val thumbnailDao = database.thumbnailCacheDao()
    suspend fun loadThumbnailKeys(
        fileIds: List<Long>,
        width: Int,
        height: Int,
        etagsByFileId: Map<Long, String?>,
        accountScope: String,
    ): List<ThumbnailKey> {
        if (fileIds.isEmpty()) {
            return emptyList()
        }

        val rows = thumbnailDao.rows(fileIds, width, height)
        val staleRows = mutableListOf<ThumbnailCacheEntity>()
        val keys = rows.mapNotNull { row ->
            val thumbnailKey = ThumbnailKey(
                accountScope = accountScope,
                fileId = row.fileId,
                width = width,
                height = height,
                etag = etagsByFileId[row.fileId],
            )
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
            thumbnailDao.delete(staleRows.map { it.fileId }, width, height)
            thumbnailFileStore.delete(staleRows.map { it.relativePath })
        }

        return keys
    }

    suspend fun saveThumbnails(
        previews: List<ThumbnailPreview>,
        width: Int,
        height: Int,
        etagsByFileId: Map<Long, String?>,
        accountScope: String,
    ) {
        if (previews.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val rows = previews.map { preview ->
            val thumbnailKey = ThumbnailKey(
                accountScope = accountScope,
                fileId = preview.fileId,
                width = width,
                height = height,
                etag = etagsByFileId[preview.fileId],
            )
            val storedFile = thumbnailFileStore.save(
                key = thumbnailKey,
                bytes = preview.bytes,
            )
            ThumbnailCacheEntity(
                fileId = preview.fileId,
                width = width,
                height = height,
                mimeType = preview.mimeType,
                cacheKey = storedFile.cacheKey,
                relativePath = storedFile.relativePath,
                cachedAtEpochMillis = now,
            )
        }

        thumbnailDao.upsert(rows)
    }

}
