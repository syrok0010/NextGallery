package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.ingestRemotePhotos
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository

class MemoriesRepository(
    private val memories: MemoriesApiClient,
    private val cacheRepository: TimelineCacheRepository,
    private val identityRegistry: MediaIdentityRegistry,
) : RemoteTimelineSource {
    override suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot? =
        bestEffort { cacheRepository.loadTimelineSnapshot(credentials) }.getOrNull()

    override suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val config = memories.config()
        val dayDtos = memories.days()
        val days = dayDtos.map { day ->
            TimelineDay(
                dayId = day.dayid,
                count = day.count,
            )
        }
        val preloadedPhotoDtos = dayDtos
            .flatMap { it.detail }
            .distinctBy { it.fileid }
        val preloadedItems = preloadedPhotoDtos.toIdentifiedMediaItems()
        val loadedDayIds = dayDtos
            .filter { it.detail.size == it.count }
            .mapTo(mutableSetOf()) { it.dayid }

        val snapshot = TimelineSnapshotAssembler.assemble(
            config = config.toMemoriesConfig(),
            days = days,
            mediaItems = preloadedItems,
            loadedDayIds = loadedDayIds,
        )

        bestEffort { cacheRepository.saveTimelineSnapshot(credentials, snapshot) }
        // The online index is authoritative here. Re-reading the materialized
        // cache would intentionally drop index-only remote slots before the UI
        // gets a chance to render the complete online timeline.
        return snapshot
    }

    override suspend fun loadTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    ): List<MediaItem> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val photoDtos = memories
            .dayDetails(dayIds.joinToString(","))
            .distinctBy { it.fileid }
        val items = photoDtos.toIdentifiedMediaItems()

        bestEffort {
            cacheRepository.saveDayDetails(items, dayIds.toSet())
        }
        return items
    }

    private suspend fun List<MemoriesPhotoDto>.toIdentifiedMediaItems(): List<MediaItem> =
        ingestRemotePhotos(this, identityRegistry)
}
