package com.syrok0010.nextgallery.data.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST
import retrofit2.http.Url

interface NextcloudAuthApi {
    @POST("index.php/login/v2")
    suspend fun startLogin(): LoginFlowInitDto

    @FormUrlEncoded
    @POST
    suspend fun pollLogin(
        @Url endpoint: String,
        @Field("token") token: String,
    ): LoginFlowCredentialsDto
}

@Serializable
data class LoginFlowInitDto(
    val poll: LoginFlowPollDto,
    val login: String,
)

@Serializable
data class LoginFlowPollDto(
    val token: String,
    val endpoint: String,
)

@Serializable
data class LoginFlowCredentialsDto(
    val server: String,
    @SerialName("loginName")
    val loginName: String,
    @SerialName("appPassword")
    val appPassword: String,
)
