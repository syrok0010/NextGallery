package com.syrok0010.nextgallery.core.network.auth

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Url

/** Endpoint-level access to Nextcloud Login Flow v2 and the current-user OCS call. */
class NextcloudAuthClient(private val transport: NextcloudTransport) {
    suspend fun startLogin(serverUrl: String): LoginFlowInitDto = api(serverUrl).startLogin()

    suspend fun pollLogin(
        serverUrl: String,
        endpoint: String,
        token: String,
    ): LoginFlowCredentialsDto = api(serverUrl).pollLogin(endpoint, token)

    suspend fun currentUser(serverUrl: String, authorization: String): NextcloudUserDto =
        userApi(serverUrl).currentUser(authorization).ocs.data

    private fun api(serverUrl: String): NextcloudAuthApi =
        transport
            .retrofit(transport.normalizeBaseUrl(serverUrl), transport.baseClient)
            .create(NextcloudAuthApi::class.java)

    private fun userApi(serverUrl: String): NextcloudUserApi =
        transport
            .retrofit(transport.normalizeBaseUrl(serverUrl), transport.baseClient)
            .create(NextcloudUserApi::class.java)
}

private interface NextcloudAuthApi {
    @POST("index.php/login/v2")
    suspend fun startLogin(): LoginFlowInitDto

    @FormUrlEncoded
    @POST
    suspend fun pollLogin(
        @Url endpoint: String,
        @Field("token") token: String,
    ): LoginFlowCredentialsDto
}

private interface NextcloudUserApi {
    @Headers("OCS-APIRequest: true")
    @GET("ocs/v2.php/cloud/user?format=json")
    suspend fun currentUser(
        @Header("Authorization") authorization: String,
    ): OcsResponseDto<NextcloudUserDto>
}

@Serializable
data class LoginFlowInitDto(val poll: LoginFlowPollDto, val login: String)

@Serializable
data class LoginFlowPollDto(val token: String, val endpoint: String)

@Serializable
data class LoginFlowCredentialsDto(
    val server: String,
    @SerialName("loginName") val loginName: String,
    @SerialName("appPassword") val appPassword: String,
)

@Serializable
data class OcsResponseDto<T>(val ocs: OcsBodyDto<T>)

@Serializable
data class OcsBodyDto<T>(val data: T)

@Serializable
data class NextcloudUserDto(val id: String)
