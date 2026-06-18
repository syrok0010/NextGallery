package com.syrok0010.nextgallery.data.auth

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.ApiFactory
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
                LoginPollResult.Failed(LoginPollFailure.Http(error.code()))
            }
        } catch (error: IOException) {
            LoginPollResult.Failed(LoginPollFailure.Network, isRecoverable = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoginPollResult.Failed(LoginPollFailure.Unknown)
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
    data class Failed(
        val failure: LoginPollFailure,
        val isRecoverable: Boolean = false,
    ) : LoginPollResult
}

sealed interface LoginPollFailure {
    data class Http(val code: Int) : LoginPollFailure
    data object Network : LoginPollFailure
    data object Unknown : LoginPollFailure
}
