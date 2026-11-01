package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
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

internal class MemoriesRepository(
    private val transport: NextcloudTransport,
    private val cacheRepository: TimelineCacheRepository,
    private val identityRegistry: MediaIdentityRegistry,
    private val favorites: CloudFavoriteMetadata,
) : RemoteTimelineSource {
    override suspend fun loadCachedTimeline(credentials: AccountCredentials): TimelineSnapshot? =
        bestEffort { cacheRepository.loadTimelineSnapshot(credentials) }.getOrNull()

    override suspend fun loadInitialTimeline(credentials: AccountCredentials): TimelineSnapshot {
        val read = favorites.begin(credentials)
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
        if (!favorites.isCurrent(read)) throw kotlinx.coroutines.CancellationException()
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

        val corrected = favorites.ingest(read, snapshot.items) { items ->
            val byId = items.associateBy { it.remoteFileId }
            cacheRepository.saveTimelineSnapshot(credentials, snapshot.copy(slots = snapshot.slots.map { slot ->
                slot.copy(mediaItem = slot.mediaItem?.let { old -> byId.getValue(old.remoteFileId) })
            }), preservedFileIds = favorites.fileIdsNewerThan(read))
        }
        val byId = corrected.associateBy { it.remoteFileId }
        // The online index is authoritative here. Re-reading the materialized
        // cache would intentionally drop index-only remote slots before the UI
        // gets a chance to render the complete online timeline.
        return snapshot.copy(slots = snapshot.slots.map { slot ->
            slot.copy(mediaItem = slot.mediaItem?.let { byId.getValue(it.remoteFileId) })
        })
    }

    override suspend fun loadTimelineDays(
        credentials: AccountCredentials,
        dayIds: List<Int>,
    ): List<MediaItem> {
        if (dayIds.isEmpty()) {
            return emptyList()
        }

        val read = favorites.begin(credentials)
        val api = transport.memoriesApi(credentials)
        val photoDtos = api
            .dayDetails(dayIds.joinToString(","))
            .distinctBy { it.fileid }
        if (!favorites.isCurrent(read)) throw kotlinx.coroutines.CancellationException()
        val items = photoDtos.toIdentifiedMediaItems()

        return favorites.ingest(read, items) { corrected ->
            cacheRepository.saveDayDetails(corrected, dayIds.toSet())
        }
    }

    suspend fun clearCache() {
        bestEffort { favorites.clear { cacheRepository.clear() } }
    }

    internal suspend fun List<MemoriesPhotoDto>.toIdentifiedMediaItems(): List<MediaItem> {
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

    private fun MemoriesPhotoDto.sourceIdentity() =
        MediaSourceIdentity(
            source = MediaSourceKind.Memories,
            sourceKey = fileid.toString(),
        )
}
