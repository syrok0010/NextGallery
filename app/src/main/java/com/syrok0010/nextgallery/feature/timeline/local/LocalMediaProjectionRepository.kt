package com.syrok0010.nextgallery.data.local

import androidx.room.withTransaction
import com.syrok0010.nextgallery.data.cache.IdentifiedLocalMedia
import com.syrok0010.nextgallery.data.cache.NextGalleryDatabase
import com.syrok0010.nextgallery.data.cache.toLocalMediaEntity
import com.syrok0010.nextgallery.data.cache.toMediaItem
import com.syrok0010.nextgallery.data.memories.MediaItem

class LocalMediaProjectionRepository(
    private val database: NextGalleryDatabase,
) : LocalMediaProjectionStore {
    private val dao = database.localMediaDao()

    override suspend fun loadLocalMediaProjection(): List<MediaItem> =
        dao.projection().map(IdentifiedLocalMedia::toMediaItem)

    override suspend fun saveLocalMediaBatch(items: List<MediaItem>) {
        if (items.isNotEmpty()) {
            dao.upsert(items.map(MediaItem::toLocalMediaEntity))
        }
    }

    override suspend fun finishLocalMediaReconciliation(contentUris: Set<String>) {
        database.withTransaction {
            dao.contentUris().filterNot { it in contentUris }.chunked(500).forEach { stale ->
                dao.deleteUris(stale)
            }
        }
    }
}
