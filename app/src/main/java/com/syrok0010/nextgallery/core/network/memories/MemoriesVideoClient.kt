package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

/** Endpoint-level access to Memories video configuration and manifests. */
class MemoriesVideoClient(
    private val transport: NextcloudTransport,
    private val client: OkHttpClient,
) {
    suspend fun configuration(url: String): MemoriesVideoConfigDto = api(url).configuration(url)

    suspend fun playlist(url: String): ResponseBody = api(url).playlist(url)

    private fun api(url: String): MemoriesVideoApi =
        transport
            .retrofit(
                url.substringBeforeLast('/') + "/",
                client,
            ).create(MemoriesVideoApi::class.java)
}

private interface MemoriesVideoApi {
    @GET
    suspend fun configuration(@Url url: String): MemoriesVideoConfigDto

    @GET
    suspend fun playlist(@Url url: String): ResponseBody
}
