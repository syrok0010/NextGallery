package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.auth.LoginFlowPollDto
import com.syrok0010.nextgallery.core.network.auth.NextcloudAuthClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import kotlinx.coroutines.CancellationException
import retrofit2.HttpException

class NextcloudLoginRepository(
    private val auth: NextcloudAuthClient,
    private val transport: NextcloudTransport,
) : LoginGateway {
    override suspend fun checkMemories(credentials: AccountCredentials): MemoriesCompatibility =
        try {
            MemoriesApiClient(transport) { credentials }
                .describe()
                ?.let { memoriesCompatibility(it.version) }
                ?: MemoriesCompatibility.Unavailable
        } catch (error: CancellationException) {
            throw error
        } catch (_: IOException) {
            MemoriesCompatibility.NetworkError
        } catch (_: kotlinx.serialization.SerializationException) {
            MemoriesCompatibility.UnknownVersion
        } catch (_: Exception) {
            MemoriesCompatibility.CheckFailed
        }

    override suspend fun startLogin(serverUrl: String): LoginSession {
        val normalizedServerUrl = NextcloudTransport.normalizeServerOrigin(serverUrl) + "/"
        val response = auth.startLogin(normalizedServerUrl)

        return LoginSession(
            serverUrl = normalizedServerUrl,
            loginUrl = response.login,
            pollEndpoint = response.poll.endpoint,
            pollToken = response.poll.token,
        )
    }

    override suspend fun pollLogin(session: LoginSession): LoginPollResult {
        val response = try {
            auth.pollLogin(
                LoginFlowPollDto(token = session.pollToken, endpoint = session.pollEndpoint),
            )
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
        val serverUrl = NextcloudTransport.normalizeServerOrigin(response.server) + "/"
        val userId = try {
            auth.currentUser(response).id
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
