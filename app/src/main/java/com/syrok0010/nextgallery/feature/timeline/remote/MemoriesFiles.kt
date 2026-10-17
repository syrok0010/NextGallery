package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.serialization.Serializable
import retrofit2.http.GET
import retrofit2.http.Path

internal interface MemoriesFilesApi {
    @GET("apps/memories/api/image/info/{id}")
    suspend fun info(@Path("id") fileId: Long): ImageInfoDto
}

/**
 * [filename] is the path from the user's files root, [basename] the file name, [etag] the DAV
 * `getetag` without quotes.
 */
@Serializable
internal data class ImageInfoDto(
    val filename: String,
    val basename: String,
    val etag: String? = null,
)

/**
 * Location of a Memories file in the user's DAV files: [path] from the files root, [name].
 * [etag] matches DAV `getetag` without quotes and pins writes to this exact file.
 */
internal data class RemoteFile(val path: String, val name: String, val etag: String? = null)

/**
 * Bridges Memories file ids to Nextcloud DAV. Memories has no write API, so writes go through
 * DAV paths of the account's uid.
 */
internal class MemoriesFiles(private val transport: NextcloudTransport) {
    suspend fun file(credentials: AccountCredentials, fileId: Long): RemoteFile {
        val info = api(credentials).info(fileId)
        return RemoteFile(
            path = info.filename,
            name = info.basename,
            etag = info.etag?.takeIf(String::isNotBlank),
        )
    }

    private fun api(credentials: AccountCredentials) =
        transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(MemoriesFilesApi::class.java)
}
