package com.syrok0010.nextgallery.data.memories

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import retrofit2.http.GET
import retrofit2.http.Path

interface MemoriesApi {
    @GET("apps/memories/api/config")
    suspend fun config(): MemoriesConfigDto

    @GET("apps/memories/api/days")
    suspend fun days(): List<MemoriesDayDto>

    @GET("apps/memories/api/days/{ids}")
    suspend fun dayDetails(
        @Path(value = "ids", encoded = true) ids: String,
    ): List<MemoriesPhotoDto>
}

@Serializable
data class MemoriesConfigDto(
    val version: String,
    @SerialName("timeline_path")
    val timelinePath: String? = null,
    @SerialName("albums_enabled")
    val albumsEnabled: Boolean = false,
    @SerialName("recognize_enabled")
    val recognizeEnabled: Boolean = false,
    @SerialName("preview_generator_enabled")
    val previewGeneratorEnabled: Boolean = false,
)

@Serializable
data class MemoriesDayDto(
    val dayid: Int,
    val count: Int,
    val detail: List<MemoriesPhotoDto> = emptyList(),
)

@Serializable
data class MemoriesPhotoDto(
    val fileid: Long,
    val dayid: Int,
    val w: Int? = null,
    val h: Int? = null,
    val etag: String? = null,
    val basename: String? = null,
    val epoch: Long? = null,
    val mimetype: String? = null,
    val auid: String? = null,
    @SerialName("isvideo")
    val isVideo: Int? = null,
    @SerialName("video_duration")
    val videoDuration: Long? = null,
    @SerialName("isfavorite")
    val isFavorite: JsonElement? = null,
)
