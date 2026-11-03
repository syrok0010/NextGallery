package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.memories.MemoriesVideoClient
import java.io.IOException
import okhttp3.OkHttpClient
import retrofit2.HttpException

internal class MemoriesVideoDiscovery(
    private val transport: NextcloudTransport,
    private val client: OkHttpClient,
) {
    private val memories = MemoriesVideoClient(transport, client)

    suspend fun qualities(
        original: RemoteVideoOriginal,
        clientId: String,
    ): List<RemoteVideoQuality> {
        val master = original.masterPlaylist(clientId)
        try {
            val config = memories.configuration(original.configurationUri)
            if (config.vodDisable) return emptyList()
            val manifest = memories.playlist(master).use { it.string() }
            return MemoriesVideoManifest.qualities(master, manifest)
        } catch (error: HttpException) {
            // Memories 10 redirects to the original when it needs no transcoding.
            if (error.code() == 303) return emptyList()
            throw IOException("Video discovery failed: ${error.code()}", error)
        }
    }
}
