package com.syrok0010.nextgallery.data.cache

import androidx.room.withTransaction
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.buildTimelineSlots

class TimelineCacheRepository(
    private val database: TimelineCacheDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
) {
    private val dao = database.timelineCacheDao()

    suspend fun loadTimelineSnapshot(credentials: AccountCredentials): TimelineSnapshot? {
        val metadata = dao.metadata() ?: return null
        if (metadata.serverUrl != credentials.normalizedServerUrl()) {
            return null
        }

        val days = dao.timelineDays().map { it.toTimelineDay() }
        if (days.isEmpty()) {
            return null
        }

        val mediaItems = dao.mediaItems().map { it.toMediaItem() }
        val itemsByDay = mediaItems.groupBy { it.dayId }
        val loadedDayIds = dao.loadedDayIds().toSet()

        return TimelineSnapshot(
            config = metadata.toMemoriesConfig(),
            days = days,
            slots = buildTimelineSlots(days, itemsByDay),
            loadedDayIds = loadedDayIds,
            totalDayCount = days.size,
            totalMediaCountHint = days.sumOf { it.count },
        )
    }

    suspend fun saveTimelineSnapshot(
        credentials: AccountCredentials,
        snapshot: TimelineSnapshot,
    ) {
        val normalizedServerUrl = credentials.normalizedServerUrl()
        val existingMetadata = dao.metadata()
        if (existingMetadata != null && existingMetadata.serverUrl != normalizedServerUrl) {
            clear()
        }

        val oldCounts = dao.timelineDayCounts().associate { it.dayId to it.count }
        val newCounts = snapshot.days.associate { it.dayId to it.count }
        val invalidatedDayIds = oldCounts
            .filter { (dayId, oldCount) -> newCounts[dayId] != oldCount }
            .keys
        val staleThumbnailRows = thumbnailRowsForDays(invalidatedDayIds)
        val now = System.currentTimeMillis()

        database.withTransaction {
            dao.upsertMetadata(snapshot.config.toCacheMetadataEntity(normalizedServerUrl, now))
            dao.deleteTimelineDays()
            dao.upsertTimelineDays(snapshot.days.mapIndexed { index, day -> day.toEntity(index) })

            if (invalidatedDayIds.isNotEmpty()) {
                dao.deleteLoadedDays(invalidatedDayIds)
                dao.deleteMediaItemsForDays(invalidatedDayIds)
            }

            if (staleThumbnailRows.isNotEmpty()) {
                dao.deleteThumbnailRowsForFileIds(staleThumbnailRows.map { it.fileId })
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
                dao.upsertMediaItems(items.map { it.toEntity() })
            }
            if (loadedDayIds.isNotEmpty()) {
                dao.upsertLoadedDays(
                    loadedDayIds.map { LoadedDayEntity(dayId = it, loadedAtEpochMillis = now) },
                )
            }
        }
    }

    suspend fun loadThumbnails(
        fileIds: List<Long>,
        width: Int,
        height: Int,
        etagsByFileId: Map<Long, String?>,
    ): List<ThumbnailPreview> {
        if (fileIds.isEmpty()) {
            return emptyList()
        }

        val rows = dao.thumbnailRows(fileIds, width, height)
        val staleRows = mutableListOf<ThumbnailCacheEntity>()
        val previews = rows.mapNotNull { row ->
            val expectedCacheKey = thumbnailFileStore.cacheKey(row.fileId, width, height, etagsByFileId[row.fileId])
            if (row.cacheKey != expectedCacheKey) {
                staleRows += row
                return@mapNotNull null
            }

            val bytes = thumbnailFileStore.load(row.relativePath)
            if (bytes == null) {
                staleRows += row
                null
            } else {
                ThumbnailPreview(
                    fileId = row.fileId,
                    requestId = 0,
                    mimeType = row.mimeType,
                    bytes = bytes,
                )
            }
        }

        if (staleRows.isNotEmpty()) {
            dao.deleteThumbnailRows(staleRows.map { it.fileId }, width, height)
            thumbnailFileStore.delete(staleRows.map { it.relativePath })
        }

        return previews
    }

    suspend fun saveThumbnails(
        previews: List<ThumbnailPreview>,
        width: Int,
        height: Int,
        etagsByFileId: Map<Long, String?>,
    ) {
        if (previews.isEmpty()) {
            return
        }

        val now = System.currentTimeMillis()
        val rows = previews.map { preview ->
            val storedFile = thumbnailFileStore.save(
                fileId = preview.fileId,
                width = width,
                height = height,
                etag = etagsByFileId[preview.fileId],
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

        dao.upsertThumbnailRows(rows)
    }

    suspend fun clear() {
        database.withTransaction {
            dao.deleteAllThumbnailRows()
            dao.deleteAllLoadedDays()
            dao.deleteAllMediaItems()
            dao.deleteAllTimelineDays()
            dao.deleteMetadata()
        }
        thumbnailFileStore.clear()
    }

    private suspend fun thumbnailRowsForDays(dayIds: Collection<Int>): List<ThumbnailCacheEntity> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val fileIds = dao.fileIdsForDays(dayIds)
        if (fileIds.isEmpty()) {
            return emptyList()
        }

        return dao.thumbnailRowsForFileIds(fileIds)
    }

    private suspend fun saveSnapshotDetailsInTransaction(
        snapshot: TimelineSnapshot,
        now: Long,
    ) {
        val items = snapshot.items
        if (items.isNotEmpty()) {
            dao.upsertMediaItems(items.map { it.toEntity() })
        }
        if (snapshot.loadedDayIds.isNotEmpty()) {
            dao.upsertLoadedDays(
                snapshot.loadedDayIds.map { LoadedDayEntity(dayId = it, loadedAtEpochMillis = now) },
            )
        }
    }

    private fun AccountCredentials.normalizedServerUrl(): String {
        return serverUrl.trim().trimEnd('/')
    }
}
