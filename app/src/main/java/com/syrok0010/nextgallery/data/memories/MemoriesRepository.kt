package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey

class MemoriesRepository(
    private val transport: NextcloudTransport,
    private val multipreviewClient: MemoriesMultipreviewClient,
    private val cacheRepository: TimelineCacheRepository,
) {
    suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot? {
        return runCatching { cacheRepository.loadTimelineSnapshot(credentials) }.getOrNull()
    }

    suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val api = transport.memoriesApi(credentials)
        val config = api.config()
        val dayDtos = api.days()
        val days = dayDtos.map { day ->
            TimelineDay(
                dayId = day.dayid,
                count = day.count,
            )
        }
        val preloadedItems = dayDtos
            .flatMap { it.detail }
            .distinctBy { it.fileid }
            .map { it.toMediaItem() }
        val loadedDayIds = dayDtos
            .filter { it.count == 0 || it.detail.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.dayid }

        val snapshot = TimelineSnapshotAssembler.assemble(
            config = config.toMemoriesConfig(),
            days = days,
            mediaItems = preloadedItems,
            loadedDayIds = loadedDayIds,
        )

        val refreshedCachedSnapshot = runCatching {
            cacheRepository.saveTimelineSnapshot(credentials, snapshot)
            cacheRepository.loadTimelineSnapshot(credentials)
        }.getOrNull()
        return refreshedCachedSnapshot ?: snapshot
    }

    suspend fun loadTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    ): List<MediaItem> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val api = transport.memoriesApi(credentials)
        val items = api.dayDetails(dayIds.joinToString(","))
            .distinctBy { it.fileid }
            .map { it.toMediaItem() }

        runCatching { cacheRepository.saveDayDetails(items, dayIds.toSet()) }
        return items
    }

    suspend fun loadThumbnails(
        credentials: AccountCredentials,
        fileIds: List<Long>,
        etagsByFileId: Map<Long, String?> = emptyMap(),
    ): List<ThumbnailKey> {
        val distinctFileIds = fileIds.distinct()
        val accountScope = credentials.thumbnailAccountScope()
        val requestedKeysByFileId = distinctFileIds.associateWith { fileId ->
            ThumbnailKey(
                accountScope = accountScope,
                fileId = fileId,
                width = DEFAULT_THUMBNAIL_SIZE,
                height = DEFAULT_THUMBNAIL_SIZE,
                etag = etagsByFileId[fileId],
            )
        }
        val cachedKeys = runCatching {
            cacheRepository.loadThumbnailKeys(
                fileIds = distinctFileIds,
                width = DEFAULT_THUMBNAIL_SIZE,
                height = DEFAULT_THUMBNAIL_SIZE,
                etagsByFileId = etagsByFileId,
                accountScope = accountScope,
            )
        }.getOrDefault(emptyList())
        val cachedFileIds = cachedKeys.mapTo(mutableSetOf()) { it.fileId }
        val missingFileIds = distinctFileIds.filterNot { it in cachedFileIds }
        if (missingFileIds.isEmpty()) {
            return cachedKeys
        }

        val remotePreviews = multipreviewClient.loadThumbnails(
            credentials = credentials,
            fileIds = missingFileIds,
            width = DEFAULT_THUMBNAIL_SIZE,
            height = DEFAULT_THUMBNAIL_SIZE,
        )
        val storedRemoteKeys = runCatching {
            cacheRepository.saveThumbnails(
                previews = remotePreviews,
                width = DEFAULT_THUMBNAIL_SIZE,
                height = DEFAULT_THUMBNAIL_SIZE,
                etagsByFileId = etagsByFileId,
                accountScope = accountScope,
            )
            remotePreviews.mapNotNull { preview -> requestedKeysByFileId[preview.fileId] }
        }.getOrDefault(emptyList())

        return cachedKeys + storedRemoteKeys
    }

    suspend fun clearCache() {
        runCatching { cacheRepository.clear() }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIZE = 512
    }

    private fun AccountCredentials.thumbnailAccountScope(): String {
        return "${NextcloudTransport.normalizeServerOrigin(serverUrl)}|$loginName"
    }
}
