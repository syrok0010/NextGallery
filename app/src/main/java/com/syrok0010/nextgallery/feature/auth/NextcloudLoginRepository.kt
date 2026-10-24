package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CancellationException
import okhttp3.Credentials
import retrofit2.HttpException

class NextcloudLoginRepository(private val transport: NextcloudTransport) : LoginGateway {
    override suspend fun startLogin(serverUrl: String): LoginSession {
        val normalizedServerUrl = transport.normalizeBaseUrl(serverUrl)
        val api = transport.nextcloudAuthApi(normalizedServerUrl)
        val response = api.startLogin()

        return LoginSession(
            serverUrl = normalizedServerUrl,
            loginUrl = response.login,
            pollEndpoint = response.poll.endpoint,
            pollToken = response.poll.token,
        )
    }

    override suspend fun pollLogin(session: LoginSession): LoginPollResult {
        val response = try {
            val api = transport.nextcloudAuthApi(session.serverUrl)
            api.pollLogin(session.pollEndpoint, session.pollToken)
        } catch (error: HttpException) {
            return if (error.code() == 404) {
                LoginPollResult.Pending
            } else {
                LoginPollResult.Failed(LoginPollFailure.Http(error.code()))
            }
        } catch (_: IOException) {
            return LoginPollResult.Failed(LoginPollFailure.Network, isRecoverable = true)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return LoginPollResult.Failed(LoginPollFailure.Unknown)
        }
        val serverUrl = transport.normalizeBaseUrl(response.server)
        val userId = try {
            transport
                .nextcloudUserApi(serverUrl)
                .currentUser(Credentials.basic(response.loginName, response.appPassword))
                .ocs.data.id
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        // A successful poll spends the token: polling again would only wait for the timeout.
        if (userId.isNullOrBlank()) return LoginPollResult.Failed(LoginPollFailure.Account)
        return LoginPollResult.Ready(
            AccountCredentials(
                serverUrl = serverUrl,
                loginName = response.loginName,
                appPassword = response.appPassword,
                userId = userId,
            ),
        )
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
    data class Failed(val failure: LoginPollFailure, val isRecoverable: Boolean = false) :
        LoginPollResult
}

sealed interface LoginPollFailure {
    data class Http(val code: Int) : LoginPollFailure
    data object Network : LoginPollFailure

    /** Login succeeded, but the account uid could not be read. */
    data object Account : LoginPollFailure
    data object Unknown : LoginPollFailure
}
