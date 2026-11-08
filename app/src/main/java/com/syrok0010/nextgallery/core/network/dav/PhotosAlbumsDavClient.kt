package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.Dispatchers
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
    suspend fun containsFile(album: AlbumPath, fileId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val account = credentials()
            val request = transport
                .authenticatedRequestBuilder(
                    account,
                    albumUrl(account.serverUrl, album).toString(),
                    accept = "application/xml",
                ).method(
                    PROPFIND,
                    encodeDav(PropFind()).toRequestBody(XML_MEDIA_TYPE.toMediaType()),
                ).header("Depth", "1")
                .build()
            transport.baseClient.newCall(request).awaitUse { response ->
                when {
                    response.code == 404 -> false

                    response.code != 207 -> throw DavHttpException(response.code, PROPFIND)

                    else -> {
                        val body = response.body?.byteStream() ?: throw IOException("No DAV body")
                        body.use {
                            decodeMultistatus(it).responses.any { member ->
                                member.propstats
                                    .firstOrNull(
                                        PropStat::found,
                                    )?.prop
                                    ?.fileId
                                    ?.trim()
                                    ?.toLongOrNull() == fileId
                            }
                        }
                    }
                }
            }
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
        const val XML_MEDIA_TYPE = "application/xml; charset=utf-8"
    }
}
