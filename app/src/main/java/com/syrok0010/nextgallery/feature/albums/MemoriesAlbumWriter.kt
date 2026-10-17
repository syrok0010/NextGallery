package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import retrofit2.http.GET
import retrofit2.http.Path

internal interface AlbumWriteApi {
    @GET("apps/memories/api/image/info/{id}")
    suspend fun info(@Path("id") fileId: Long): ImageInfoDto
}

/** [filename] is the path from the user's files root, [basename] the file name. */
@Serializable
internal data class ImageInfoDto(val filename: String, val basename: String)

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
internal class MemoriesAlbumWriter(private val transport: NextcloudTransport) {
    suspend fun create(credentials: AccountCredentials, name: String): AlbumLocation.Remote {
        val user = credentials.userId
        val request = transport
            .authenticatedRequestBuilder(
                credentials,
                albumUrl(credentials.serverUrl, AlbumAddress(user, name)).toString(),
                accept = "*/*",
            ).method("MKCOL", null)
            .build()
        transport.baseClient.newCall(request).await().use { response ->
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
        val api = api(credentials)
        val permits = Semaphore(PARALLEL_COPIES)
        val outcomes = coroutineScope {
            fileIds
                .distinct()
                .map { fileId ->
                    async {
                        permits.withPermit {
                            try {
                                val info = api.info(fileId)
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

    private suspend fun albumExists(credentials: AccountCredentials, album: AlbumAddress): Boolean {
        val request = transport
            .authenticatedRequestBuilder(
                credentials,
                albumUrl(credentials.serverUrl, album).toString(),
                accept = "*/*",
            ).method("PROPFIND", null)
            .header("Depth", "0")
            .build()
        return transport.baseClient.newCall(request).await().use { it.isSuccessful }
    }

    private suspend fun copy(
        credentials: AccountCredentials,
        source: HttpUrl,
        destination: HttpUrl,
    ): AlbumCopyOutcome {
        val request: Request = transport
            .authenticatedRequestBuilder(credentials, source.toString(), accept = "*/*")
            .method("COPY", null)
            .header("Destination", destination.toString())
            .build()
        return transport.baseClient.newCall(request).await().use { albumCopyOutcome(it.code) }
    }

    private fun api(credentials: AccountCredentials) =
        transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(AlbumWriteApi::class.java)

    private companion object {
        const val PARALLEL_COPIES = 4
    }
}

/** Unlike `execute()`, cancelling the caller cancels the call, so logout stops pending writes. */
internal suspend fun Call.await(): Response =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { _, value, _ -> value.close() }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )
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
    davRoot(serverUrl)
        .addPathSegment("files")
        .addPathSegment(user)
        .apply { path.split('/').filter(String::isNotEmpty).forEach(::addPathSegment) }
        .build()

private fun davRoot(serverUrl: String): HttpUrl.Builder =
    NextcloudTransport
        .normalizeServerOrigin(serverUrl)
        .toHttpUrl()
        .newBuilder()
        .addPathSegment("remote.php")
        .addPathSegment("dav")
