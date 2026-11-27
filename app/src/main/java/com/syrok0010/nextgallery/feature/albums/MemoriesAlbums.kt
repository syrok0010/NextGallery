package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaIdentityRegistry
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.AddToAlbumResult
import com.syrok0010.nextgallery.core.network.dav.AlbumPath
import com.syrok0010.nextgallery.core.network.dav.CreateAlbumResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavHttpException
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.dav.awaitUse
import com.syrok0010.nextgallery.core.network.dav.davRoot
import com.syrok0010.nextgallery.core.network.memories.MemoriesAlbumDto
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.library.ingestRemotePhotos
import com.syrok0010.nextgallery.feature.upload.temporary
import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
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
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.w3c.dom.Element
import org.xml.sax.InputSource

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

internal data class AlbumRemovalBatch(val removedIds: Set<Long>, val failed: Int) {
    val summary get() = AlbumRemovalResult(removedIds.size, failed)
}

internal data class AlbumRemovalResult(val removed: Int, val failed: Int)

/** Port for Nextcloud Photos/Memories albums: reading catalog/contents and writing membership. */
internal interface RemoteAlbums {
    suspend fun loadCatalog(): RemoteAlbumsCatalog
    fun loadContents(location: AlbumLocation.Remote): Flow<AlbumContentsBatch>
    suspend fun create(name: String): AlbumLocation.Remote
    suspend fun add(album: AlbumAddress, fileIds: List<Long>): AlbumWriteResult
    suspend fun addFile(file: DavFilePath, album: AlbumAddress): AlbumCopyOutcome
    suspend fun containsFile(album: AlbumAddress, fileId: Long?): Boolean
    suspend fun remove(location: AlbumLocation.Remote, fileIds: Set<Long>): AlbumRemovalBatch
}

/** Nextcloud Memories + Photos DAV adapter for [RemoteAlbums]. */
internal class MemoriesAlbums(
    private val credentials: AccountCredentials,
    private val transport: NextcloudTransport,
    private val memories: MemoriesApiClient,
    private val dav: PhotosAlbumsDavClient,
    private val files: FilesDavClient,
    private val identities: MediaIdentityRegistry,
) : RemoteAlbums {
    private val removalClient =
        transport.baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
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
                ingestRemotePhotos(photos, identities).forEach { item ->
                    item.remoteFileId?.let { items[it] = item }
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

    /** Removing membership never addresses the original in DAV files or the album collection itself. */
    override suspend fun remove(
        location: AlbumLocation.Remote,
        fileIds: Set<Long>,
    ): AlbumRemovalBatch = withContext(Dispatchers.IO) {
        val address = requireNotNull(AlbumAddress.of(location))
        require(address.owner == credentials.userId) { "Only owned albums support removal" }
        val root = albumUrl(credentials.serverUrl, address)
        val request = transport.authenticatedRequestBuilder(credentials, root.toString(), "application/xml")
            .method("PROPFIND", MEMBERSHIP_PROPERTIES.toRequestBody("application/xml".toMediaType()))
            .header("Depth", "1")
            .build()
        val members = removalClient.newCall(request).awaitUse {
            if (it.code != 207) throw IOException("HTTP ${it.code} listing album membership")
            albumMemberUrls(root, it.body.string())
        }
        val permits = Semaphore(PARALLEL_COPIES)
        val outcomes = coroutineScope {
            fileIds.map { id ->
                async {
                    permits.withPermit {
                        val url = members[id] ?: return@withPermit id to true
                        id to try {
                            val delete = transport.authenticatedRequestBuilder(credentials, url.toString(), "*/*")
                                .delete()
                                .build()
                            removalClient.newCall(delete).awaitUse { it.isSuccessful }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            false
                        }
                    }
                }
            }.awaitAll()
        }
        AlbumRemovalBatch(
            outcomes.filter { it.second }.mapTo(mutableSetOf()) { it.first },
            outcomes.count { !it.second },
        )
    }

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
        const val MEMBERSHIP_PROPERTIES =
            """<d:propfind xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:prop><oc:fileid/></d:prop></d:propfind>"""
    }
}

internal fun albumUrl(serverUrl: String, address: AlbumAddress): HttpUrl =
    davRoot(serverUrl)
        .addPathSegment("photos")
        .addPathSegment(address.owner)
        .addPathSegment("albums")
        .addPathSegment(address.name)
        .build()

/** Accept only exact immediate children of this album on this server, even for hostile DAV XML. */
internal fun albumMemberUrls(root: HttpUrl, xml: String): Map<Long, HttpUrl> {
    require(!xml.contains("<!DOCTYPE", ignoreCase = true) && !xml.contains("<!ENTITY", ignoreCase = true))
    val builder = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
    }.newDocumentBuilder().apply {
        setEntityResolver { _, _ -> throw IOException("External XML entities are forbidden") }
    }
    val doc = builder.parse(InputSource(StringReader(xml)))
    require(doc.documentElement.localName == "multistatus" && doc.documentElement.namespaceURI == "DAV:")
    val responses = doc.getElementsByTagNameNS("DAV:", "response")
    val parent = root.encodedPath.trimEnd('/') + "/"
    return buildMap {
        for (index in 0 until responses.length) {
            val response = responses.item(index) as Element
            val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent
                ?: throw IOException("Missing membership href")
            val url = root.resolve(href) ?: throw IOException("Invalid membership href")
            require(url.scheme == root.scheme && url.host == root.host && url.port == root.port)
            if (url.encodedPath.trimEnd('/') == root.encodedPath.trimEnd('/')) continue
            require(url.encodedPath.startsWith(parent) && '/' !in url.encodedPath.removePrefix(parent))
            require(url.query == null && url.fragment == null)
            val propstats = response.getElementsByTagNameNS("DAV:", "propstat")
            var fileId: Long? = null
            for (propIndex in 0 until propstats.length) {
                val propstat = propstats.item(propIndex) as Element
                val status = propstat.getElementsByTagNameNS("DAV:", "status").item(0)?.textContent
                if (status?.split(' ')?.getOrNull(1) == "200") {
                    fileId = propstat.getElementsByTagNameNS("http://owncloud.org/ns", "fileid")
                        .item(0)?.textContent?.toLongOrNull() ?: fileId
                }
            }
            val id = requireNotNull(fileId) { "Missing membership file id" }
            require(id > 0 && url.pathSegments.last().startsWith("$id-"))
            require(put(id, url) == null) { "Duplicate membership file id" }
        }
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
