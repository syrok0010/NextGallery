package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.AlbumPath
import com.syrok0010.nextgallery.core.network.dav.PhotosDavClient
import com.syrok0010.nextgallery.core.network.dav.davFileUrl
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

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

/**
 * Photos albums deduplicate by file id and answer a repeated COPY with 409 Conflict. DAV also
 * answers 409 when the album itself is missing, so callers confirm the album on conflicts.
 */
internal fun albumCopyOutcome(code: Int): AlbumCopyOutcome =
    when (code) {
        in 200..299 -> AlbumCopyOutcome.Added
        409 -> AlbumCopyOutcome.AlreadyPresent
        else -> AlbumCopyOutcome.Failed
    }

internal class AlbumAlreadyExistsException(name: String) : IOException("Album $name already exists")

/**
 * Writes Photos albums through Nextcloud DAV, the contract Memories Web uses: Memories API
 * itself has no album write endpoint.
 */
internal class MemoriesAlbumWriter(
    private val dav: PhotosDavClient,
    private val memories: MemoriesApiClient,
) {
    internal constructor(transport: NextcloudTransport) : this(
        PhotosDavClient(transport),
        MemoriesApiClient(transport),
    )

    suspend fun create(credentials: AccountCredentials, name: String): AlbumLocation.Remote {
        val user = credentials.userId
        dav.mkcol(credentials, AlbumPath(user, name)).let { response ->
            // DAV answers MKCOL on an existing collection with 405 Method Not Allowed.
            if (response.code == 405) throw AlbumAlreadyExistsException(name)
            if (!response.isSuccessful) {
                throw IOException(
                    "HTTP ${response.code} creating album",
                )
            }
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
                                val info = memories.imageInfo(credentials, fileId)
                                copy(
                                    credentials,
                                    source = fileUrl(
                                        credentials.serverUrl,
                                        credentials.userId,
                                        info.filename,
                                    ),
                                    destination = albumUrl(credentials.serverUrl, album)
                                        .newBuilder()
                                        .addPathSegment(info.basename)
                                        .build(),
                                )
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
        val confirmed = present == 0 || albumExists(credentials, album)
        return AlbumAddResult(
            added = outcomes.count { it == AlbumCopyOutcome.Added },
            alreadyPresent = if (confirmed) present else 0,
            failed = outcomes.count {
                it == AlbumCopyOutcome.Failed
            } +
                if (confirmed) 0 else present,
        )
    }

    /** Adds a file by its DAV URL; works before Memories has indexed a fresh upload. */
    suspend fun addFile(
        credentials: AccountCredentials,
        source: HttpUrl,
        album: AlbumAddress,
    ): AlbumCopyOutcome {
        val outcome = copy(
            credentials,
            source,
            albumUrl(credentials.serverUrl, album)
                .newBuilder()
                .addPathSegment(source.pathSegments.last())
                .build(),
        )
        return if (outcome == AlbumCopyOutcome.AlreadyPresent && !albumExists(credentials, album)) {
            AlbumCopyOutcome.Failed
        } else {
            outcome
        }
    }

    /**
     * Confirms a 409 against the album. A failed check must not discard the whole batch, so an
     * unconfirmed conflict counts as a failure for that file only.
     */
    private suspend fun albumExists(credentials: AccountCredentials, album: AlbumAddress): Boolean =
        try {
            dav.propfind(credentials, AlbumPath(album.owner, album.name)).isSuccessful
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }

    private suspend fun copy(
        credentials: AccountCredentials,
        source: HttpUrl,
        destination: HttpUrl,
    ): AlbumCopyOutcome = albumCopyOutcome(dav.copy(credentials, source, destination).code)

    private companion object {
        const val PARALLEL_COPIES = 4
    }
}

internal fun albumUrl(serverUrl: String, album: AlbumAddress): HttpUrl =
    davRoot(serverUrl)
        .addPathSegment("photos")
        .addPathSegment(album.owner)
        .addPathSegment("albums")
        .addPathSegment(album.name)
        .build()

/** [path] is relative to the user's files root as reported by Memories image info. */
internal fun fileUrl(serverUrl: String, user: String, path: String): HttpUrl =
    davFileUrl(serverUrl, user, path)

private fun davRoot(serverUrl: String): HttpUrl.Builder =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")
