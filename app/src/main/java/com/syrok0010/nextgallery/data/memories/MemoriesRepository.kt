package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.ApiFactory

class MemoriesRepository(
    private val apiFactory: ApiFactory,
) {
    suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val api = apiFactory.memoriesApi(credentials)
        val config = api.config()
        val days = api.days()
        val preloadedPhotos = days.flatMap { it.detail }
        val dayIdsToLoad = days
            .asSequence()
            .filter { it.count > 0 && it.detail.isEmpty() }
            .take(12)
            .map { it.dayid }
            .toList()

        val lazyPhotos = if (dayIdsToLoad.isEmpty()) {
            emptyList()
        } else {
            api.dayDetails(dayIdsToLoad.joinToString(","))
        }

        val photos = (preloadedPhotos + lazyPhotos)
            .distinctBy { it.fileid }
            .map { it.toMediaItem(credentials.serverUrl) }
            .sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds ?: 0L }.thenByDescending { it.fileId })

        return TimelineSnapshot(
            config = config.toMemoriesConfig(),
            totalDayCount = days.size,
            totalMediaCountHint = days.sumOf { it.count },
            items = photos,
        )
    }
}
