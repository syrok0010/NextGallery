package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.RemoteTimelineSource
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository

class MemoriesRepository(
    private val transport: NextcloudTransport,
    private val cacheRepository: TimelineCacheRepository,
    private val identityRegistry: MediaIdentityRegistry,
) : RemoteTimelineSource {
    override suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot? {
        return bestEffort { cacheRepository.loadTimelineSnapshot(credentials) }.getOrNull()
    }

    override suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val api = transport.memoriesApi(credentials)
        val config = api.config()
        val dayDtos = api.days()
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
            .filter { it.count == 0 || it.detail.isNotEmpty() }
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

        val api = transport.memoriesApi(credentials)
        val photoDtos = api.dayDetails(dayIds.joinToString(","))
            .distinctBy { it.fileid }
        val items = photoDtos.toIdentifiedMediaItems()

        bestEffort {
            cacheRepository.saveDayDetails(items, dayIds.toSet())
        }
        return items
    }

    suspend fun clearCache() {
        bestEffort { cacheRepository.clear() }
    }

    private suspend fun List<MemoriesPhotoDto>.toIdentifiedMediaItems(): List<MediaItem> {
        val candidates = map { photo ->
            mediaIdentityCandidate(
                source = photo.sourceIdentity(),
                auid = photo.auid,
                buid = photo.buid,
            )
        }
        val resolution = identityRegistry.resolve(candidates)
        return map { photo ->
            photo.toMediaItem(resolution.mediaIds.getValue(photo.sourceIdentity()))
        }
    }

    private fun MemoriesPhotoDto.sourceIdentity() = MediaSourceIdentity(
        source = MediaSourceKind.Memories,
        sourceKey = fileid.toString(),
    )
}
