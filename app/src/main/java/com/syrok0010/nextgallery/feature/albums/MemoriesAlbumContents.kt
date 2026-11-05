package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class MemoriesAlbumContents(
    private val memories: MemoriesApiClient,
    private val identities: MediaIdentityRegistry,
    private val favorites: CloudFavoriteMetadata,
    private val persist: suspend (List<MediaItem>) -> Unit,
) {
    fun load(location: AlbumLocation.Remote, credentials: AccountCredentials) =
        flow {
            val read = favorites.begin(credentials)
            val days = memories.albumDays(location.clusterId).distinctBy { it.dayid }
            val total = days.sumOf { it.count }
            val items = linkedMapOf<Long, MediaItem>()
            suspend fun add(photos: List<MemoriesPhotoDto>) {
                val unique = photos.distinctBy { it.fileid }
                val candidates = unique.map { photo ->
                    mediaIdentityCandidate(
                        MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
                        auid = photo.auid,
                        buid = photo.buid,
                    )
                }
                val resolution = identities.resolve(candidates)
                val incoming = unique.map { photo ->
                    photo.toMediaItem(
                        resolution.mediaIds.getValue(
                            MediaSourceIdentity(MediaSourceKind.Memories, photo.fileid.toString()),
                        ),
                    )
                }
                favorites.ingest(read, incoming, persist).forEach { item ->
                    items[requireNotNull(item.remoteFileId)] = item
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
                add(memories.albumPhotos(ids.joinToString(","), location.clusterId))
                emit(batch())
            }
        }.flowOn(Dispatchers.Default)
}
