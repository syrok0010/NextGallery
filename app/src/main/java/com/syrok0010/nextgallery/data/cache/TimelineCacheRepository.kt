package com.syrok0010.nextgallery.data.cache

import androidx.room.withTransaction
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import com.syrok0010.nextgallery.domain.media.MediaId

class TimelineCacheRepository(
    private val database: TimelineCacheDatabase,
    private val thumbnailFileStore: ThumbnailFileStore,
    private val mediaIdFactory: () -> MediaId = MediaId::generate,
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
        val loadedDayIds = dao.loadedDayIds().toSet()

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

    suspend fun resolveRemoteMediaIds(fileIds: Collection<Long>): Map<Long, MediaId> {
        val identities = fileIds.associateWith { fileId ->
            MediaSourceIdentity(MediaSource.Memories, fileId.toString())
        }
        val mediaIds = resolveMediaIds(identities.values)
        return identities.mapValues { (_, identity) -> mediaIds.getValue(identity) }
    }

    suspend fun resolveLocalMediaIds(contentUris: Collection<String>): Map<String, MediaId> {
        val identities = contentUris.associateWith { contentUri ->
            MediaSourceIdentity(MediaSource.Local, contentUri)
        }
        val mediaIds = resolveMediaIds(identities.values)
        return identities.mapValues { (_, identity) -> mediaIds.getValue(identity) }
    }

    suspend fun resolveMediaIds(identities: Collection<MediaSourceIdentity>): Map<MediaSourceIdentity, MediaId> {
        if (identities.isEmpty()) return emptyMap()

        val distinctIdentities = identities.toSet()
        return database.withTransaction {
            val existingIds = distinctIdentities
                .groupBy { it.source }
                .values
                .flatMap { sourceIdentities ->
                    dao.mediaIdentities(
                        source = sourceIdentities.first().source.name,
                        sourceKeys = sourceIdentities.map { it.sourceKey },
                    )
                }
                .associate { entity ->
                    MediaSourceIdentity(MediaSource.valueOf(entity.source), entity.sourceKey) to MediaId(entity.mediaId)
                }
            val missingIdentities = distinctIdentities
                .filterNot(existingIds::containsKey)
                .associateWith { mediaIdFactory() }
            if (missingIdentities.isNotEmpty()) {
                dao.upsertMediaIdentities(
                    missingIdentities.map { (identity, mediaId) ->
                        MediaIdentityEntity(
                            source = identity.source.name,
                            sourceKey = identity.sourceKey,
                            mediaId = mediaId.value,
                        )
                    },
                )
            }
            existingIds + missingIdentities
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

        val rows = dao.thumbnailRows(fileIds, width, height)
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
            dao.deleteThumbnailRows(staleRows.map { it.fileId }, width, height)
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

        dao.upsertThumbnailRows(rows)
    }

    suspend fun clear() {
        database.withTransaction {
            dao.deleteAllThumbnailRows()
            dao.deleteAllLoadedDays()
            dao.deleteAllMediaItems()
            dao.deleteAllMediaIdentities()
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
        return NextcloudTransport.normalizeServerOrigin(serverUrl)
    }
}
