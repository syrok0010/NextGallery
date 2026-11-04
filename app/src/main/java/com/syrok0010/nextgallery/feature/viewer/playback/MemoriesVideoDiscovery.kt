package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.network.memories.MemoriesVideoClient
import java.io.IOException
import retrofit2.HttpException

/** [memories] must not follow redirects: Memories answers 303 when no transcoding is needed. */
internal class MemoriesVideoDiscovery(private val memories: MemoriesVideoClient) {
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
