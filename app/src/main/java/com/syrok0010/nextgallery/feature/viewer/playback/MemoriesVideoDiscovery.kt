package com.syrok0010.nextgallery.feature.viewer.playback

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import java.io.IOException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MemoriesVideoDiscovery(private val client: OkHttpClient) {
    suspend fun qualities(original: RemoteVideoOriginal, clientId: String): List<RemoteVideoQuality> {
        val master = original.masterPlaylist(clientId)
        val config = json.decodeFromString<VideoConfigDto>(read(original.configurationUri))
        if (config.vodDisable) return emptyList()
        return MemoriesVideoManifest.qualities(master, read(master))
    }

    private suspend fun read(uri: String): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(uri).build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val body = response.use {
                        if (!it.isSuccessful) throw IOException("Video discovery failed: ${it.code}")
                        it.body.string()
                    }
                    continuation.resume(body)
                } catch (error: IOException) {
                    continuation.resumeWithException(error)
                }
            }
        })
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

@Serializable
private data class VideoConfigDto(
    val version: String,
    @SerialName("vod_disable") val vodDisable: Boolean = false,
)
