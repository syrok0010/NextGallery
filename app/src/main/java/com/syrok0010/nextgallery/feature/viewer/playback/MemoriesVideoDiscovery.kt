package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import java.io.IOException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Url

internal class MemoriesVideoDiscovery(
    private val transport: NextcloudTransport,
    private val client: OkHttpClient,
) {
    suspend fun qualities(original: RemoteVideoOriginal, clientId: String): List<RemoteVideoQuality> {
        val master = original.masterPlaylist(clientId)
        val api = transport.retrofit(original.configurationUri.toHttpUrl().resolve(".")!!.toString(), client)
            .create(MemoriesVideoApi::class.java)
        try {
            val config = api.configuration(original.configurationUri)
            if (config.vodDisable) return emptyList()
            val manifest = api.playlist(master).use { it.string() }
            return MemoriesVideoManifest.qualities(master, manifest)
        } catch (error: HttpException) {
            throw IOException("Video discovery failed: ${error.code()}", error)
        }
    }
}

private interface MemoriesVideoApi {
    @GET
    suspend fun configuration(@Url url: String): VideoConfigDto

    @GET
    suspend fun playlist(@Url url: String): ResponseBody
}

@Serializable
private data class VideoConfigDto(
    val version: String,
    @SerialName("vod_disable") val vodDisable: Boolean = false,
)
