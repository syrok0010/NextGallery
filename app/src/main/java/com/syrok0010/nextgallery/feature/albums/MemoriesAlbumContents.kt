package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesDayDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
internal class MemoriesAlbumContents(
    private val memories: MemoriesApiClient,
    private val identities: MediaIdentityRegistry,
) {
    internal constructor(
        transport: NextcloudTransport,
        identities: MediaIdentityRegistry,
    ) : this(MemoriesApiClient(transport), identities)

    fun load(location: AlbumLocation.Remote, credentials: AccountCredentials) =
        flow {
            val days = memories.albumDays(credentials, location.clusterId).distinctBy { it.dayid }
            val total = days.sumOf { it.count }
            val items = linkedMapOf<Long, MediaItem>()
            suspend fun add(photos: List<MemoriesPhotoDto>) {
                val candidates = photos.distinctBy { it.fileid }.map { photo ->
                    mediaIdentityCandidate(
                        MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
                        auid = photo.auid,
                        buid = photo.buid,
                    )
                }
                val resolution = identities.resolve(candidates)
                photos.forEach { photo ->
                    items[photo.fileid] = photo.toMediaItem(
                        resolution.mediaIds.getValue(
                            MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
                        ),
                    )
                }
            }

            fun batch() =
                AlbumContentsBatch(
                    items.values
                        .sortedWith(
                            compareByDescending<MediaItem> { it.dayId }
                                .thenByDescending { it.takenAtEpochSeconds }
                                .thenByDescending { it.remoteFileId },
                        ).distinctBy { it.mediaId },
                    total,
                    items.size,
                )
            add(days.flatMap { it.detail })
            emit(batch())
            days.filter { it.count > it.detail.size }.map { it.dayid }.chunked(10).forEach { ids ->
                add(memories.albumPhotos(credentials, ids.joinToString(","), location.clusterId))
                emit(batch())
            }
        }.flowOn(Dispatchers.Default)
}
