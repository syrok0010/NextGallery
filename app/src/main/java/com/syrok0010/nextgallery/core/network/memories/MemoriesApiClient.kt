package com.syrok0010.nextgallery.core.network.memories

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.await
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/** Endpoint-level access to the authenticated Memories JSON and binary APIs. */
class MemoriesApiClient(private val transport: NextcloudTransport) {
    suspend fun describe(credentials: AccountCredentials): MemoriesDescribeDto =
        api(credentials).describe()

    suspend fun config(credentials: AccountCredentials): MemoriesConfigDto =
        api(credentials).config()

    suspend fun albumConfig(credentials: AccountCredentials): JsonObject =
        api(credentials).albumConfig()

    suspend fun days(credentials: AccountCredentials): List<MemoriesDayDto> =
        api(credentials).days()

    suspend fun dayDetails(credentials: AccountCredentials, ids: String): List<MemoriesPhotoDto> =
        api(credentials).dayDetails(ids)

    suspend fun albumDays(credentials: AccountCredentials, album: String): List<MemoriesDayDto> =
        api(credentials).albumDays(album)

    suspend fun albumPhotos(
        credentials: AccountCredentials,
        ids: String,
        album: String,
    ): List<MemoriesPhotoDto> = api(credentials).albumPhotos(ids, album)

    suspend fun albums(credentials: AccountCredentials): List<JsonObject> =
        api(credentials).albums()

    suspend fun imageInfo(credentials: AccountCredentials, fileId: Long): MemoriesImageInfoDto =
        api(credentials).imageInfo(fileId)

    suspend fun multipreview(
        credentials: AccountCredentials,
        request: MemoriesMultipreviewRequest,
    ): ByteArray {
        val body = transportJson
            .encodeToString(
                request,
            ).toRequestBody("application/json".toMediaType())
        val httpRequest = transport
            .authenticatedRequestBuilder(
                credentials,
                "${transport.normalizeBaseUrl(
                    credentials.serverUrl,
                )}apps/memories/api/image/multipreview",
                accept = "application/octet-stream",
            ).post(body)
            .build()
        return withContext(Dispatchers.IO) {
            transport.baseClient.newCall(httpRequest).await().use { response ->
                if (!response.isSuccessful) {
                    error("Multipreview failed with HTTP ${response.code}")
                }
                checkNotNull(response.body).bytes()
            }
        }
    }

    private val transportJson get() = transport.json

    private fun api(credentials: AccountCredentials): MemoriesApi =
        transport
            .retrofit(
                transport.normalizeBaseUrl(credentials.serverUrl),
                transport.authenticatedClient(credentials),
            ).create(MemoriesApi::class.java)
}

private interface MemoriesApi {
    @GET("apps/memories/api/describe")
    suspend fun describe(): MemoriesDescribeDto

    @GET("apps/memories/api/config")
    suspend fun config(): MemoriesConfigDto

    @GET("apps/memories/api/config")
    suspend fun albumConfig(): JsonObject

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
    suspend fun albums(): List<JsonObject>

    @GET("apps/memories/api/image/info/{id}")
    suspend fun imageInfo(@Path("id") fileId: Long): MemoriesImageInfoDto
}
