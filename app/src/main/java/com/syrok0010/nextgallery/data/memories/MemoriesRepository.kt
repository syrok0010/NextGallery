package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.ApiFactory

class MemoriesRepository(
    private val apiFactory: ApiFactory,
) {
    suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val api = apiFactory.memoriesApi(credentials)
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
            .map { it.toMediaItem(credentials.serverUrl) }
        val preloadedItemsByDay = preloadedItems.groupBy { it.dayId }
        val loadedDayIds = dayDtos
            .filter { it.count == 0 || it.detail.isNotEmpty() }
            .mapTo(mutableSetOf()) { it.dayid }

        return TimelineSnapshot(
            config = config.toMemoriesConfig(),
            days = days,
            slots = buildTimelineSlots(days, preloadedItemsByDay),
            loadedDayIds = loadedDayIds,
            totalDayCount = days.size,
            totalMediaCountHint = days.sumOf { it.count },
        )
    }

    suspend fun loadTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    ): List<MediaItem> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val api = apiFactory.memoriesApi(credentials)
        return api.dayDetails(dayIds.joinToString(","))
            .distinctBy { it.fileid }
            .map { it.toMediaItem(credentials.serverUrl) }
    }
}
