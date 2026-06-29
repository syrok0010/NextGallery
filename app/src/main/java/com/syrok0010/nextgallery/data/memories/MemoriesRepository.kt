package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.cache.TimelineCacheRepository
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport

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

        runCatching { cacheRepository.saveTimelineSnapshot(credentials, snapshot) }
        return snapshot
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
    ): List<ThumbnailPreview> {
        val distinctFileIds = fileIds.distinct()
        val cachedPreviews = runCatching {
            cacheRepository.loadThumbnails(
                fileIds = distinctFileIds,
                width = DEFAULT_THUMBNAIL_SIZE,
                height = DEFAULT_THUMBNAIL_SIZE,
                etagsByFileId = etagsByFileId,
            )
        }.getOrDefault(emptyList())
        val cachedFileIds = cachedPreviews.mapTo(mutableSetOf()) { it.fileId }
        val missingFileIds = distinctFileIds.filterNot { it in cachedFileIds }
        if (missingFileIds.isEmpty()) {
            return cachedPreviews
        }

        val remotePreviews = multipreviewClient.loadThumbnails(
            credentials = credentials,
            fileIds = missingFileIds,
            width = DEFAULT_THUMBNAIL_SIZE,
            height = DEFAULT_THUMBNAIL_SIZE,
        )
        runCatching {
            cacheRepository.saveThumbnails(
                previews = remotePreviews,
                width = DEFAULT_THUMBNAIL_SIZE,
                height = DEFAULT_THUMBNAIL_SIZE,
                etagsByFileId = etagsByFileId,
            )
        }

        return cachedPreviews + remotePreviews
    }

    suspend fun clearCache() {
        runCatching { cacheRepository.clear() }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIZE = 512
    }
}
