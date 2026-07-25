package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import com.syrok0010.nextgallery.data.thumbnail.thumbnailAccountScope

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

    suspend fun ensureThumbnails(
        credentials: AccountCredentials,
        requestedKeys: List<ThumbnailKey>,
    ): List<ThumbnailKey> {
        if (requestedKeys.isEmpty()) {
            return emptyList()
        }

        val accountScope = credentials.thumbnailAccountScope()
        val firstKey = requestedKeys.first()
        require(requestedKeys.all { key ->
            key.accountScope == accountScope &&
                key.width == firstKey.width &&
                key.height == firstKey.height
        }) {
            "A thumbnail batch must belong to one account and use one size"
        }
        val keysByFileId = requestedKeys.distinctBy { it.fileId }.associateBy { it.fileId }
        val distinctFileIds = keysByFileId.keys.toList()
        val etagsByFileId = keysByFileId.mapValues { (_, key) -> key.etag }
        val cachedKeys = runCatching {
            cacheRepository.loadThumbnailKeys(
                fileIds = distinctFileIds,
                width = firstKey.width,
                height = firstKey.height,
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
            width = firstKey.width,
            height = firstKey.height,
        )
        val storedRemoteKeys = runCatching {
            cacheRepository.saveThumbnails(
                previews = remotePreviews,
                width = firstKey.width,
                height = firstKey.height,
                etagsByFileId = etagsByFileId,
                accountScope = accountScope,
            )
            remotePreviews.mapNotNull { preview -> keysByFileId[preview.fileId] }
        }.getOrDefault(emptyList())

        return cachedKeys + storedRemoteKeys
    }

    suspend fun clearCache() {
        runCatching { cacheRepository.clear() }
    }

}
