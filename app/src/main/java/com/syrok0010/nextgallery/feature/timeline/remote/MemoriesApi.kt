package com.syrok0010.nextgallery.data.memories

import retrofit2.http.GET
import retrofit2.http.Path

interface MemoriesApi {
    @GET("apps/memories/api/describe")
    suspend fun describe(): MemoriesDescribeDto

    @GET("apps/memories/api/config")
    suspend fun config(): MemoriesConfigDto

    @GET("apps/memories/api/days")
    suspend fun days(): List<MemoriesDayDto>

    @GET("apps/memories/api/days/{ids}")
    suspend fun dayDetails(
        @Path(value = "ids", encoded = true) ids: String,
    ): List<MemoriesPhotoDto>
}
