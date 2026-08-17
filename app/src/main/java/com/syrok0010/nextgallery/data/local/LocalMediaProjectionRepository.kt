package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.cache.LocalMediaEntity
import com.syrok0010.nextgallery.data.cache.TimelineCacheDatabase
import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.cache.toLocalMediaEntity
import com.syrok0010.nextgallery.data.cache.toMediaItem
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId

class LocalMediaProjectionRepository(
    database: TimelineCacheDatabase,
    private val identityRepository: TimelineCacheRepository,
) : LocalMediaProjectionStore {
    private val dao = database.timelineCacheDao()

    override suspend fun loadLocalMediaProjection(): List<MediaItem> =
        dao.localMediaProjection().map(LocalMediaEntity::toMediaItem)

    override suspend fun resolveLocalMediaIds(contentUris: Collection<String>): Map<String, MediaId> =
        identityRepository.resolveLocalMediaIds(contentUris)

    override suspend fun saveLocalMediaBatch(items: List<MediaItem>) {
        if (items.isNotEmpty()) {
            dao.upsertLocalMediaProjection(items.map(MediaItem::toLocalMediaEntity))
        }
    }

    override suspend fun finishLocalMediaReconciliation(contentUris: Set<String>) {
        if (contentUris.isEmpty()) {
            dao.deleteAllLocalMedia()
        } else {
            dao.deleteLocalMediaNotIn(contentUris)
        }
    }
}
