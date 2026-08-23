package com.syrok0010.nextgallery.data.cache

import androidx.room.withTransaction
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MediaIdentityRegistry
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

class TimelineCacheRepository(
    private val database: NextGalleryDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
    private val identityRegistry: MediaIdentityRegistry,
) {
    private val timelineDao = database.memoriesTimelineDao()
    private val thumbnailDao = database.thumbnailCacheDao()

    suspend fun loadTimelineSnapshot(credentials: AccountCredentials): TimelineSnapshot? {
        val metadata = timelineDao.metadata() ?: return null
        if (metadata.serverUrl != credentials.normalizedServerUrl()) {
            return null
        }

        val days = timelineDao.timelineDays().map { it.toTimelineDay() }
        if (days.isEmpty()) {
            return null
        }

        val mediaItems = timelineDao.mediaItems().map { it.toMediaItem() }
        val loadedDayIds = timelineDao.loadedDayIds().toSet()

        return TimelineSnapshotAssembler.assemble(
            config = metadata.toMemoriesConfig(),
            days = days,
            mediaItems = mediaItems,
            loadedDayIds = loadedDayIds,
        )
    }

    suspend fun saveTimelineSnapshot(
        credentials: AccountCredentials,
        snapshot: TimelineSnapshot,
    ) {
        val normalizedServerUrl = credentials.normalizedServerUrl()
        val oldCounts = timelineDao.timelineDayCounts().associate { it.dayId to it.count }
        val newCounts = snapshot.days.associate { it.dayId to it.count }
        val invalidatedDayIds = oldCounts
            .filter { (dayId, oldCount) -> newCounts[dayId] != oldCount }
            .keys
        val staleThumbnailRows = thumbnailRowsForDays(invalidatedDayIds)
        val now = System.currentTimeMillis()

        database.withTransaction {
            timelineDao.upsertMetadata(
                requireNotNull(snapshot.config) { "Only a Memories timeline can be cached" }
                    .toCacheMetadataEntity(normalizedServerUrl, now),
            )
            timelineDao.deleteTimelineDays()
            timelineDao.upsertTimelineDays(snapshot.days.mapIndexed { index, day -> day.toEntity(index) })

            if (invalidatedDayIds.isNotEmpty()) {
                timelineDao.deleteLoadedDays(invalidatedDayIds)
                timelineDao.deleteMediaItemsForDays(invalidatedDayIds)
            }

            if (staleThumbnailRows.isNotEmpty()) {
                thumbnailDao.deleteForFileIds(staleThumbnailRows.map { it.fileId })
            }

            saveSnapshotDetailsInTransaction(snapshot, now)
        }
        thumbnailFileStore.delete(staleThumbnailRows.map { it.relativePath })
    }

    suspend fun saveDayDetails(
        items: List<MediaItem>,
        loadedDayIds: Set<Int>,
    ) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            if (items.isNotEmpty()) {
                timelineDao.upsertMediaItems(items.map { it.toMemoriesMediaEntity() })
            }
            if (loadedDayIds.isNotEmpty()) {
                timelineDao.upsertLoadedDays(
                    loadedDayIds.map { LoadedDayEntity(dayId = it, loadedAtEpochMillis = now) },
                )
            }
        }
    }

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

    suspend fun clear() {
        database.withTransaction {
            thumbnailDao.deleteAll()
            timelineDao.deleteAllLoadedDays()
            timelineDao.deleteAllMediaItems()
            identityRegistry.removeSource(MediaSourceKind.Memories)
            timelineDao.deleteTimelineDays()
            timelineDao.deleteMetadata()
        }
        thumbnailFileStore.clear()
    }

    private suspend fun thumbnailRowsForDays(dayIds: Collection<Int>): List<ThumbnailCacheEntity> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val fileIds = timelineDao.fileIdsForDays(dayIds)
        if (fileIds.isEmpty()) {
            return emptyList()
        }

        return thumbnailDao.rowsForFileIds(fileIds)
    }

    private suspend fun saveSnapshotDetailsInTransaction(
        snapshot: TimelineSnapshot,
        now: Long,
    ) {
        val items = snapshot.items
        if (items.isNotEmpty()) {
            timelineDao.upsertMediaItems(items.map { it.toMemoriesMediaEntity() })
        }
        if (snapshot.loadedDayIds.isNotEmpty()) {
            timelineDao.upsertLoadedDays(
                snapshot.loadedDayIds.map { LoadedDayEntity(dayId = it, loadedAtEpochMillis = now) },
            )
        }
    }

    private fun AccountCredentials.normalizedServerUrl(): String {
        return NextcloudTransport.normalizeServerOrigin(serverUrl)
    }
}
