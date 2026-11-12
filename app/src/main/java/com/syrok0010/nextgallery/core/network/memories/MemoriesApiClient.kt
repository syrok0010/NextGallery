package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.coroutines.executeAsync
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Headers
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
    /** Null when Memories is not installed or disabled: Nextcloud answers its routes with 404 or 410. */
    suspend fun describe(): MemoriesDescribeDto? =
        try {
            api().describe()
        } catch (error: HttpException) {
            if (error.code() == 404 || error.code() == 410) null else throw error
        }

    suspend fun config(): MemoriesConfigDto = api().config()

    suspend fun days(): List<MemoriesDayDto> = api().days()

    suspend fun dayDetails(ids: String): List<MemoriesPhotoDto> = api().dayDetails(ids)

    suspend fun albumDays(album: String): List<MemoriesDayDto> = api().albumDays(album)

    suspend fun albumPhotos(ids: String, album: String): List<MemoriesPhotoDto> =
        api().albumPhotos(ids, album)

    suspend fun albums(): List<MemoriesAlbumDto> = api().albums()

    suspend fun imageInfo(fileId: Long): MemoriesImageInfoDto = api().imageInfo(fileId)

    /**
     * Streams previews of [fileIds] at [width]×[height], aspect ratio kept, in server order as
     * they arrive. Files without a preview are absent, not errors. A block read blocks the IO
     * thread, so cancellation takes effect between blocks.
     */
    fun multipreview(fileIds: List<Long>, width: Int, height: Int): Flow<MemoriesPreviewDto> {
        val files = fileIds.distinct()
        if (files.isEmpty()) return emptyFlow()
        val request = MultipreviewRequest(
            files.mapIndexed { index, fileId ->
                MultipreviewFileRequest(fileId, width, height, a = "1", reqid = index + 1)
            },
        )
        return flow {
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
            transport.baseClient.newCall(httpRequest).executeAsync().use { response ->
                if (!response.isSuccessful) {
                    error("Multipreview failed with HTTP ${response.code}")
                }
                val source = checkNotNull(response.body).source()
                while (true) {
                    val block = readMultipreviewBlock(source, transport.json) ?: break
                    val fileId = files.getOrNull(block.reqid - 1) ?: continue
                    emit(MemoriesPreviewDto(fileId, block.type, block.bytes))
                }
            }
        }.flowOn(Dispatchers.IO)
    }

    /**
     * The preview of [fileId] at [width]×[height], aspect ratio kept. Unlike [multipreview], the
     * server generates a missing preview, so a just uploaded file gets one.
     */
    suspend fun preview(fileId: Long, width: Int, height: Int): MemoriesPreviewDto =
        api().preview(fileId, width, height, keepAspectRatio = 1).use { body ->
            MemoriesPreviewDto(fileId, body.contentType()?.toString().orEmpty(), body.bytes())
        }

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

    @Headers("Accept: image/*")
    @GET("apps/memories/api/image/preview/{id}")
    suspend fun preview(
        @Path("id") fileId: Long,
        @Query("x") width: Int,
        @Query("y") height: Int,
        @Query("a") keepAspectRatio: Int,
    ): ResponseBody

    @GET("apps/memories/api/image/info/{id}")
    suspend fun imageInfo(@Path("id") fileId: Long): MemoriesImageInfoDto
}
