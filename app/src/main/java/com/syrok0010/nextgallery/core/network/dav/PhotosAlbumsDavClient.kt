package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

enum class CreateAlbumResult { Created, AlreadyExists }

/**
 * Photos answers COPY into an album with 409 both for a file already in it and for a missing
 * album; telling them apart is up to the caller.
 */
enum class AddToAlbumResult { Added, Conflict }

/**
 * Photos albums under `remote.php/dav/photos/{owner}/albums`, the contract Memories Web uses:
 * Memories API itself has no album write endpoint. Each call reads [credentials] once. Statuses
 * without a result here fail with [DavHttpException].
 */
class PhotosAlbumsDavClient(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) {
    private val strictClient =
        transport.baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()

    suspend fun createAlbum(album: AlbumPath): CreateAlbumResult {
        val response = transport.dav(credentials(), MKCOL) { albumUrl(it, album) }
        return when {
            response.isSuccessful -> CreateAlbumResult.Created

            // DAV answers MKCOL on an existing collection with 405 Method Not Allowed.
            response.code == 405 -> CreateAlbumResult.AlreadyExists

            else -> response.unexpected(MKCOL)
        }
    }

    suspend fun albumExists(album: AlbumPath): Boolean {
        val response = transport.dav(credentials(), PROPFIND, mapOf("Depth" to "0")) {
            albumUrl(it, album)
        }
        return when {
            response.isSuccessful -> true
            response.code == 404 -> false
            else -> response.unexpected(PROPFIND)
        }
    }

    /** Checks exact membership by file id without issuing a second COPY. */
    suspend fun containsFile(album: AlbumPath, fileId: Long): Boolean {
        val account = credentials()
        return transport.propfind(account, albumUrl(account.serverUrl, album), depth = 1) { body ->
            withMultistatusResponses(body) { responses ->
                responses.any { member ->
                    member.propstats
                        .firstOrNull(PropStat::found)
                        ?.prop
                        ?.fileId
                        ?.trim()
                        ?.toLongOrNull() == fileId
                }
            }
        } ?: false
    }

    /** Copies [file] into [album] under its own name; the album deduplicates by file id. */
    suspend fun addFile(album: AlbumPath, file: DavFilePath): AddToAlbumResult {
        val account = credentials()
        val destination = albumUrl(account.serverUrl, album)
            .newBuilder()
            .addPathSegment(file.name)
            .build()
        val response = transport.dav(
            account,
            COPY,
            mapOf("Destination" to destination.toString()),
        ) {
            file.url(it)
        }
        return when {
            response.isSuccessful -> AddToAlbumResult.Added
            response.code == 409 -> AddToAlbumResult.Conflict
            else -> response.unexpected(COPY)
        }
    }

    /**
     * Removes [fileIds] from an owned [album] by deleting only exact immediate member links under
     * the album collection. Never addresses `files/` or the collection itself, and never follows
     * redirects. Returns the ids that are no longer in the album (deleted or already absent).
     */
    suspend fun removeFiles(album: AlbumPath, fileIds: Set<Long>): Set<Long> =
        withContext(Dispatchers.IO) {
            val account = credentials()
            require(album.owner == account.userId) { "Only owned albums support removal" }
            if (fileIds.isEmpty()) return@withContext emptySet()
            val root = albumUrl(account.serverUrl, album)
            val request = transport.authenticatedRequestBuilder(
                account,
                root.toString(),
                accept = "application/xml",
            ).method(
                PROPFIND,
                encodeDav(PropFind()).toRequestBody("application/xml; charset=utf-8".toMediaType()),
            ).header("Depth", "1")
                .build()
            val members = strictClient.newCall(request).awaitUse { response ->
                if (response.code != 207) throw DavHttpException(response.code, PROPFIND)
                response.body.byteStream().use { body ->
                    withMultistatusResponses(body) { responses ->
                        extractMemberUrls(root, responses)
                    }
                }
            }
            val permits = Semaphore(PARALLEL_REMOVALS)
            coroutineScope {
                fileIds.map { id ->
                    async {
                        permits.withPermit {
                            val url = members[id] ?: return@withPermit id
                            val removed = try {
                                val delete = transport
                                    .authenticatedRequestBuilder(account, url.toString(), "*/*")
                                    .delete()
                                    .build()
                                strictClient.newCall(delete).awaitUse { it.isSuccessful }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                false
                            }
                            if (removed) id else null
                        }
                    }
                }.awaitAll().filterNotNullTo(mutableSetOf())
            }
        }

    /** Accept only exact immediate children of this album on this server, even for hostile DAV XML. */
    private fun extractMemberUrls(
        root: HttpUrl,
        responses: Sequence<MultistatusResponse>,
    ): Map<Long, HttpUrl> {
        val parent = root.encodedPath.trimEnd('/') + "/"
        return buildMap {
            for (response in responses) {
                val href = response.href.trim()
                if (href.isEmpty()) throw IOException("Missing membership href")
                val url = root.resolve(href) ?: throw IOException("Invalid membership href")
                if (url.scheme != root.scheme || url.host != root.host || url.port != root.port) {
                    throw IOException("Membership href escaped origin")
                }
                if (url.encodedPath.trimEnd('/') == root.encodedPath.trimEnd('/')) continue
                if (!url.encodedPath.startsWith(parent) || '/' in url.encodedPath.removePrefix(parent)) {
                    throw IOException("Membership href escaped album collection")
                }
                if (url.query != null || url.fragment != null) {
                    throw IOException("Unexpected membership query or fragment")
                }
                val fileId = response.propstats
                    .filter(PropStat::found)
                    .mapNotNull { it.prop.fileId?.trim()?.toLongOrNull() }
                    .lastOrNull()
                    ?: throw IOException("Missing membership file id")
                if (fileId <= 0L || !url.pathSegments.last().startsWith("$fileId-")) {
                    throw IOException("Membership href does not match file id")
                }
                if (put(fileId, url) != null) throw IOException("Duplicate membership file id")
            }
        }
    }

    private fun albumUrl(serverUrl: String, album: AlbumPath): HttpUrl =
        davRoot(serverUrl)
            .addPathSegment("photos")
            .addPathSegment(album.owner)
            .addPathSegment("albums")
            .addPathSegment(album.name)
            .build()

    private companion object {
        const val MKCOL = "MKCOL"
        const val PROPFIND = "PROPFIND"
        const val COPY = "COPY"
        const val PARALLEL_REMOVALS = 4
    }
}
