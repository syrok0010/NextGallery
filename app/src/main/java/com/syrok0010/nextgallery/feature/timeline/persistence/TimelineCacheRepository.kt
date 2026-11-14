package com.syrok0010.nextgallery.feature.timeline.persistence

import androidx.room.withTransaction
import com.syrok0010.nextgallery.core.database.LoadedDayEntity
import com.syrok0010.nextgallery.core.database.MemoriesCacheMetadataEntity
import com.syrok0010.nextgallery.core.database.NextGalleryDatabase
import com.syrok0010.nextgallery.core.database.ThumbnailCacheEntity
import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler

class TimelineCacheRepository(
    private val database: NextGalleryDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
    private val identityRegistry: MediaIdentityRegistry,
) {
    private val timelineDao = database.memoriesTimelineDao()
    private val thumbnailDao = database.thumbnailCacheDao()

    suspend fun loadTimelineSnapshot(credentials: AccountCredentials): TimelineSnapshot? =
        database.withTransaction {
            val metadata = timelineDao.metadata() ?: return@withTransaction null
            if (!metadata.belongsTo(credentials)) return@withTransaction null

            val loadedDayIds = timelineDao.loadedDayIds().toSet()
            val mediaItems = timelineDao
                .mediaItems()
                .filter { it.media.dayId in loadedDayIds }
                .map { it.toMediaItem() }

            TimelineSnapshotAssembler.assembleMaterialized(
                config = metadata.toMemoriesConfig(),
                mediaItems = mediaItems,
                loadedDayIds = loadedDayIds,
            )
        }

    suspend fun saveTimelineSnapshot(credentials: AccountCredentials, snapshot: TimelineSnapshot) {
        val normalizedServerUrl = credentials.normalizedServerUrl()
        val newCounts = snapshot.days.associate { it.dayId to it.count }
        val now = System.currentTimeMillis()

        var accountChanged = false
        val staleThumbnailRows = database.withTransaction {
            // Fallback when forgetOtherAccount failed: another account's rows must not survive
            // under matching day counts.
            if (timelineDao.metadata()?.belongsTo(credentials) == false) {
                clearInTransaction()
                accountChanged = true
            }
            val oldCounts = timelineDao.timelineDayCounts().associate { it.dayId to it.count }
            val invalidatedDayIds = oldCounts
                .filter { (dayId, oldCount) -> newCounts[dayId] != oldCount }
                .keys
            val staleThumbnails = thumbnailRowsForDays(invalidatedDayIds)
            timelineDao.upsertMetadata(
                requireNotNull(snapshot.config) { "Only a Memories timeline can be cached" }
                    .toCacheMetadataEntity(normalizedServerUrl, credentials.userId, now),
            )
            timelineDao.deleteTimelineDays()
            timelineDao.upsertTimelineDays(
                snapshot.days.mapIndexed { index, day -> day.toEntity(index) },
            )

            if (invalidatedDayIds.isNotEmpty()) {
                timelineDao.deleteLoadedDays(invalidatedDayIds)
            }

            if (staleThumbnails.isNotEmpty()) {
                thumbnailDao.deleteForFileIds(staleThumbnails.map { it.fileId })
            }

            val itemsByDay = snapshot.items.groupBy { it.dayId }
            val completeTimeline = snapshot.days.all { day ->
                val fileCount = itemsByDay[day.dayId]
                    .orEmpty()
                    .mapNotNull { it.remoteFileId }
                    .toSet()
                    .size
                day.dayId in snapshot.loadedDayIds && fileCount == day.count
            }
            if (completeTimeline) {
                retireMissingAliasesInTransaction(snapshot.items)
                timelineDao.deleteAllMediaItems()
            } else {
                deleteCompletedDaysInTransaction(snapshot.loadedDayIds)
            }
            saveSnapshotDetailsInTransaction(snapshot, now)
            staleThumbnails
        }
        if (accountChanged) {
            thumbnailFileStore.clear()
        } else {
            thumbnailFileStore.delete(staleThumbnailRows.map { it.relativePath })
        }
    }

    suspend fun saveDayDetails(items: List<MediaItem>, loadedDayIds: Set<Int>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            deleteCompletedDaysInTransaction(loadedDayIds)
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

    /**
     * Drops the cache of another account. Clearing removes every Memories alias, so call it before
     * the new account's photos are resolved in the registry, or their identities go with it.
     */
    suspend fun forgetOtherAccount(credentials: AccountCredentials) {
        val dropped = database.withTransaction {
            val other = timelineDao.metadata()?.belongsTo(credentials) == false
            if (other) clearInTransaction()
            other
        }
        if (dropped) thumbnailFileStore.clear()
    }

    suspend fun clear() {
        database.withTransaction { clearInTransaction() }
        thumbnailFileStore.clear()
    }

    private suspend fun clearInTransaction() {
        thumbnailDao.deleteAll()
        timelineDao.deleteAllLoadedDays()
        timelineDao.deleteAllMediaItems()
        identityRegistry.removeSource(MediaSourceKind.Memories)
        timelineDao.deleteTimelineDays()
        timelineDao.deleteMetadata()
    }

    private fun MemoriesCacheMetadataEntity.belongsTo(credentials: AccountCredentials): Boolean =
        serverUrl == credentials.normalizedServerUrl() && userId == credentials.userId

    private suspend fun retireMissingAliasesInTransaction(items: List<MediaItem>) {
        val currentFileIds = items.map { it.remoteFileId }.toSet()
        // A day alone cannot prove disappearance: the same fileId may have moved to an unloaded day.
        timelineDao.fileIds().filterNot { it in currentFileIds }.chunked(500).forEach { fileIds ->
            database
                .mediaIdentityDao()
                .clearAliases(MediaSourceKind.Memories, fileIds.map(Long::toString))
        }
    }

    private suspend fun deleteCompletedDaysInTransaction(completedDayIds: Set<Int>) {
        completedDayIds.toList().chunked(500).forEach { dayIds ->
            timelineDao.deleteMediaItemsForDays(dayIds)
        }
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

    private suspend fun saveSnapshotDetailsInTransaction(snapshot: TimelineSnapshot, now: Long) {
        val items = snapshot.items
        if (items.isNotEmpty()) {
            timelineDao.upsertMediaItems(items.map { it.toMemoriesMediaEntity() })
        }
        if (snapshot.loadedDayIds.isNotEmpty()) {
            timelineDao.upsertLoadedDays(
                snapshot.loadedDayIds.map {
                    LoadedDayEntity(
                        dayId = it,
                        loadedAtEpochMillis = now,
                    )
                },
            )
        }
    }

    private fun AccountCredentials.normalizedServerUrl(): String =
        NextcloudTransport.normalizeServerOrigin(serverUrl)
}
