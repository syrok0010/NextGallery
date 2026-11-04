package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.dav.AddToAlbumResult
import com.syrok0010.nextgallery.core.network.dav.AlbumPath
import com.syrok0010.nextgallery.core.network.dav.CreateAlbumResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Owner and collection name of a Photos album addressed by Memories `cluster_id`. */
internal data class AlbumAddress(val owner: String, val name: String) {
    companion object {
        fun of(location: AlbumLocation.Remote): AlbumAddress? {
            val owner = location.clusterId.substringBefore('/', "")
            val name = location.clusterId.substringAfter('/', "")
            return if (owner.isEmpty() || name.isEmpty()) null else AlbumAddress(owner, name)
        }
    }
}

internal data class AlbumAddResult(val added: Int, val alreadyPresent: Int, val failed: Int)

internal enum class AlbumCopyOutcome { Added, AlreadyPresent, Failed }

internal class AlbumAlreadyExistsException(name: String) : IOException("Album $name already exists")

/** Writes Photos albums through Nextcloud DAV; album contents are looked up via Memories. */
internal class MemoriesAlbumWriter(
    private val dav: PhotosAlbumsDavClient,
    private val memories: MemoriesApiClient,
) {
    suspend fun create(credentials: AccountCredentials, name: String): AlbumLocation.Remote {
        val user = credentials.userId
        if (dav.createAlbum(AlbumPath(user, name)) == CreateAlbumResult.AlreadyExists) {
            throw AlbumAlreadyExistsException(name)
        }
        return AlbumLocation.Remote("$user/$name")
    }

    suspend fun add(
        credentials: AccountCredentials,
        album: AlbumAddress,
        fileIds: List<Long>,
    ): AlbumAddResult {
        val permits = Semaphore(PARALLEL_COPIES)
        val outcomes = coroutineScope {
            fileIds
                .distinct()
                .map { fileId ->
                    async {
                        permits.withPermit {
                            try {
                                val info = memories.imageInfo(fileId)
                                copy(DavFilePath.of(credentials.userId, info.filename), album)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                AlbumCopyOutcome.Failed
                            }
                        }
                    }
                }.awaitAll()
        }
        val present = outcomes.count { it == AlbumCopyOutcome.AlreadyPresent }
        val confirmed = present == 0 || albumExists(album)
        return AlbumAddResult(
            added = outcomes.count { it == AlbumCopyOutcome.Added },
            alreadyPresent = if (confirmed) present else 0,
            failed = outcomes.count {
                it == AlbumCopyOutcome.Failed
            } +
                if (confirmed) 0 else present,
        )
    }

    /** Adds a file by its DAV path; works before Memories has indexed a fresh upload. */
    suspend fun addFile(file: DavFilePath, album: AlbumAddress): AlbumCopyOutcome {
        val outcome = copy(file, album)
        return if (outcome == AlbumCopyOutcome.AlreadyPresent && !albumExists(album)) {
            AlbumCopyOutcome.Failed
        } else {
            outcome
        }
    }

    /**
     * Confirms a 409 against the album. A failed check must not discard the whole batch, so an
     * unconfirmed conflict counts as a failure for that file only.
     */
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
        } catch (_: DavHttpException) {
            AlbumCopyOutcome.Failed
        }

    private companion object {
        const val PARALLEL_COPIES = 4
    }
}
