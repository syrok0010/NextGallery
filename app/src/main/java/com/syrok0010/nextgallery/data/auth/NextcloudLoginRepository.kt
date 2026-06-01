package com.syrok0010.nextgallery.data.auth

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.ApiFactory
import retrofit2.HttpException

class NextcloudLoginRepository(
    private val apiFactory: ApiFactory,
) {
    suspend fun startLogin(serverUrl: String): LoginSession {
        val normalizedServerUrl = apiFactory.normalizeServerUrl(serverUrl)
        val api = apiFactory.nextcloudAuthApi(normalizedServerUrl)
        val response = api.startLogin()

        return LoginSession(
            serverUrl = normalizedServerUrl,
            loginUrl = response.login,
            pollEndpoint = response.poll.endpoint,
            pollToken = response.poll.token,
        )
    }

    suspend fun pollLogin(session: LoginSession): LoginPollResult {
        return try {
            val api = apiFactory.nextcloudAuthApi(session.serverUrl)
            val response = api.pollLogin(session.pollEndpoint, session.pollToken)
            LoginPollResult.Ready(
                AccountCredentials(
                    serverUrl = apiFactory.normalizeServerUrl(response.server),
                    loginName = response.loginName,
                    appPassword = response.appPassword,
                ),
            )
        } catch (error: HttpException) {
            if (error.code() == 404) {
                LoginPollResult.Pending
            } else {
                LoginPollResult.Failed("Login poll failed: HTTP ${error.code()}")
            }
        } catch (error: Exception) {
            LoginPollResult.Failed(error.message ?: "Login poll failed")
        }
    }
}

data class LoginSession(
    val serverUrl: String,
    val loginUrl: String,
    val pollEndpoint: String,
    val pollToken: String,
)

sealed interface LoginPollResult {
    data object Pending : LoginPollResult
    data class Ready(val credentials: AccountCredentials) : LoginPollResult
    data class Failed(val message: String) : LoginPollResult
}
