package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.await
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Endpoint-level access to the authenticated Memories JSON and binary APIs. Each request reads
 * [credentials] once, so its URL and Authorization header belong to the same session.
 */
class MemoriesApiClient(
    private val transport: NextcloudTransport,
    private val credentials: () -> AccountCredentials,
) {
    suspend fun describe(): MemoriesDescribeDto = api().describe()

    suspend fun config(): MemoriesConfigDto = api().config()

    suspend fun days(): List<MemoriesDayDto> = api().days()

    suspend fun dayDetails(ids: String): List<MemoriesPhotoDto> = api().dayDetails(ids)

    suspend fun albumDays(album: String): List<MemoriesDayDto> = api().albumDays(album)

    suspend fun albumPhotos(ids: String, album: String): List<MemoriesPhotoDto> =
        api().albumPhotos(ids, album)

    suspend fun albums(): List<MemoriesAlbumDto> = api().albums()

    suspend fun imageInfo(fileId: Long): MemoriesImageInfoDto = api().imageInfo(fileId)

    /**
     * Streams previews in server order as they arrive. Unavailable previews are absent, not
     * errors. A block read blocks the IO thread, so cancellation takes effect between blocks.
     */
    fun multipreview(request: MemoriesMultipreviewRequest): Flow<MemoriesPreviewDto> =
        flow {
            val account = credentials()
            val body = transport.json
                .encodeToString(request)
                .toRequestBody("application/json".toMediaType())
            val httpRequest = transport
                .authenticatedRequestBuilder(
                    account,
                    "${transport.normalizeBaseUrl(
                        account.serverUrl,
                    )}apps/memories/api/image/multipreview",
                    accept = "application/octet-stream",
                ).post(body)
                .build()
            transport.baseClient.newCall(httpRequest).await().use { response ->
                if (!response.isSuccessful) {
                    error("Multipreview failed with HTTP ${response.code}")
                }
                val source = checkNotNull(response.body).source()
                while (true) {
                    emit(readMemoriesPreview(source, transport.json) ?: break)
                }
            }
        }.flowOn(Dispatchers.IO)

    private fun api(): MemoriesApi {
        val account = credentials()
        return transport
            .retrofit(
                transport.normalizeBaseUrl(account.serverUrl),
                transport.authenticatedClient(account),
            ).create(MemoriesApi::class.java)
    }
}

private interface MemoriesApi {
    @GET("apps/memories/api/describe")
    suspend fun describe(): MemoriesDescribeDto

    @GET("apps/memories/api/config")
    suspend fun config(): MemoriesConfigDto

    @GET("apps/memories/api/days")
    suspend fun days(): List<MemoriesDayDto>

    @GET("apps/memories/api/days/{ids}")
    suspend fun dayDetails(@Path(value = "ids", encoded = true) ids: String): List<MemoriesPhotoDto>

    @GET("apps/memories/api/days")
    suspend fun albumDays(@Query("albums") album: String): List<MemoriesDayDto>

    @GET("apps/memories/api/days/{ids}")
    suspend fun albumPhotos(
        @Path(value = "ids", encoded = true) ids: String,
        @Query("albums") album: String,
    ): List<MemoriesPhotoDto>

    @GET("apps/memories/api/clusters/albums")
    suspend fun albums(): List<MemoriesAlbumDto>

    @GET("apps/memories/api/image/info/{id}")
    suspend fun imageInfo(@Path("id") fileId: Long): MemoriesImageInfoDto
}
