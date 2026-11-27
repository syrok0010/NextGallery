package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.network.dav.AddToAlbumResult
import com.syrok0010.nextgallery.core.network.dav.AlbumPath
import com.syrok0010.nextgallery.core.network.dav.CreateAlbumResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesAlbumDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import com.syrok0010.nextgallery.feature.upload.temporary
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class AlbumAddResult(val added: Int, val alreadyPresent: Int, val failed: Int)

internal data class AlbumWriteResult(val outcomes: Map<Long, AlbumCopyOutcome>) {
    val summary
        get() = AlbumAddResult(
            outcomes.values.count { it == AlbumCopyOutcome.Added },
            outcomes.values.count { it == AlbumCopyOutcome.AlreadyPresent },
            outcomes.values.count { it == AlbumCopyOutcome.Failed },
        )
    val presentIds get() = outcomes.filterValues { it != AlbumCopyOutcome.Failed }.keys
}

internal enum class AlbumCopyOutcome { Added, AlreadyPresent, Failed }

internal class AlbumAlreadyExistsException(name: String) : IOException("Album $name already exists")

/** Port for Nextcloud Photos/Memories albums: reading catalog/contents and writing membership. */
internal interface RemoteAlbums {
    suspend fun loadCatalog(): RemoteAlbumsCatalog
    fun loadContents(location: AlbumLocation.Remote): Flow<AlbumContentsBatch>
    suspend fun create(name: String): AlbumLocation.Remote
    suspend fun add(album: AlbumAddress, fileIds: List<Long>): AlbumWriteResult
    suspend fun addFile(file: DavFilePath, album: AlbumAddress): AlbumCopyOutcome
    suspend fun containsFile(album: AlbumAddress, fileId: Long?): Boolean
}

/** Nextcloud Memories + Photos DAV adapter for [RemoteAlbums]. */
internal class MemoriesAlbums(
    private val credentials: AccountCredentials,
    private val memories: MemoriesApiClient,
    private val dav: PhotosAlbumsDavClient,
    private val files: FilesDavClient,
    private val identities: MediaIdentityRegistry,
) : RemoteAlbums {
    override suspend fun loadCatalog(): RemoteAlbumsCatalog {
        val config = memories.config()
        if (!config.albumsEnabled) return RemoteAlbumsCatalog(emptyList(), supported = false)
        return RemoteAlbumsCatalog(
            memories
                .albums()
                .map(::remoteAlbumSummary)
                .filter { config.showHiddenAlbums || !it.name.startsWith('.') }
                .distinctBy { it.id },
        )
    }

    override fun loadContents(location: AlbumLocation.Remote): Flow<AlbumContentsBatch> =
        flow {
            val days = memories.albumDays(location.clusterId).distinctBy { it.dayid }
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
                add(memories.albumPhotos(ids.joinToString(","), location.clusterId))
                emit(batch())
            }
        }.flowOn(Dispatchers.Default)

    override suspend fun create(name: String): AlbumLocation.Remote {
        val user = credentials.userId
        if (dav.createAlbum(AlbumPath(user, name)) == CreateAlbumResult.AlreadyExists) {
            throw AlbumAlreadyExistsException(name)
        }
        return AlbumAddress(user, name).toLocation()
    }

    override suspend fun add(album: AlbumAddress, fileIds: List<Long>): AlbumWriteResult {
        val permits = Semaphore(PARALLEL_COPIES)
        val outcomes = coroutineScope {
            fileIds
                .distinct()
                .map { fileId ->
                    async {
                        permits.withPermit {
                            fileId to try {
                                files.findFile(fileId)?.let { copy(it, album) }
                                    ?: AlbumCopyOutcome.Failed
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AlbumCopyOutcome.Failed
                            }
                        }
                    }
                }.awaitAll()
        }
        val confirmed = outcomes.none { it.second == AlbumCopyOutcome.AlreadyPresent } || albumExists(album)
        return AlbumWriteResult(
            outcomes.associate { (id, outcome) ->
                id to if (!confirmed && outcome == AlbumCopyOutcome.AlreadyPresent) AlbumCopyOutcome.Failed else outcome
            },
        )
    }

    /**
     * Adds a file by its DAV path, as an upload knows it, without a lookup. A temporary failure
     * (no network, 5xx, 423, 429) is thrown, so the upload step tries again later.
     */
    override suspend fun addFile(file: DavFilePath, album: AlbumAddress): AlbumCopyOutcome {
        val outcome = copy(file, album)
        val path = AlbumPath(album.owner, album.name)
        return if (outcome == AlbumCopyOutcome.AlreadyPresent && !dav.albumExists(path)) {
            AlbumCopyOutcome.Failed
        } else {
            outcome
        }
    }

    /** Reads album membership for recovery; it never performs COPY. */
    override suspend fun containsFile(album: AlbumAddress, fileId: Long?): Boolean =
        fileId != null && dav.containsFile(AlbumPath(album.owner, album.name), fileId)

    private suspend fun albumExists(album: AlbumAddress): Boolean =
        try {
            dav.albumExists(AlbumPath(album.owner, album.name))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    /**
     * Photos albums deduplicate by file id and answer a repeated COPY with a conflict. DAV also
     * answers so when the album itself is missing, so callers confirm the album on conflicts.
     */
    private suspend fun copy(file: DavFilePath, album: AlbumAddress): AlbumCopyOutcome =
        try {
            when (dav.addFile(AlbumPath(album.owner, album.name), file)) {
                AddToAlbumResult.Added -> AlbumCopyOutcome.Added
                AddToAlbumResult.Conflict -> AlbumCopyOutcome.AlreadyPresent
            }
        } catch (error: DavHttpException) {
            if (error.temporary) throw error
            AlbumCopyOutcome.Failed
        }

    private companion object {
        const val PARALLEL_COPIES = 4
    }
}

internal fun remoteAlbumSummary(album: MemoriesAlbumDto): AlbumSummary {
    val coverId = album.cover?.takeIf { it > 0 }
    val fileId = coverId ?: album.lastAddedPhoto?.takeIf { it > 0 }
    return AlbumSummary(
        id = "remote:${album.albumId}",
        name = album.name,
        origin = AlbumOrigin.Nextcloud,
        count = album.count?.takeIf { it in 0..Int.MAX_VALUE.toLong() }?.toInt(),
        detail = album.userDisplay ?: album.user.orEmpty(),
        location = (album.clusterId ?: album.user?.let { "$it/${album.name}" })
            ?.let(AlbumLocation::Remote),
        cover = fileId?.let {
            AlbumCover.Remote(
                it,
                if (coverId != null) album.coverEtag else album.lastAddedPhotoEtag,
            )
        },
    )
}
