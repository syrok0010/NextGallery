package com.syrok0010.nextgallery.feature.auth

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.Recorded
import com.syrok0010.nextgallery.core.network.auth.NextcloudAuthClient
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

class NextcloudLoginRepositoryTest {
    @Test fun `ready login carries uid from OCS user, not the login name`() = runBlocking {
        NextcloudFixture { request ->
            when {
                request.target == "/poll" -> 200 to credentialsJson(request)
                request.target.startsWith("/ocs/v2.php/cloud/user") ->
                    200 to """{"ocs":{"meta":{"status":"ok"},"data":{"id":"anna","display-name":"Anna"}}}"""
                else -> 404 to ""
            }
        }.use { fixture ->
            val result = repository().pollLogin(session(fixture.origin))
            val credentials = (result as LoginPollResult.Ready).credentials
            assertEquals("anna@example.com", credentials.loginName)
            assertEquals("anna", credentials.userId)
            val user = fixture.requests.single { it.target.startsWith("/ocs/") }
            assertEquals(Credentials.basic("anna@example.com", "app-password"), user.headers["authorization"])
            assertEquals("true", user.headers["ocs-apirequest"])
        }
    }

    @Test fun `poll waits while the browser login is unfinished and fails on other answers`() = runBlocking {
        val cases = listOf(
            404 to LoginPollResult.Pending,
            403 to LoginPollResult.Failed(LoginPollFailure.Http(403)),
            500 to LoginPollResult.Failed(LoginPollFailure.Http(500)),
        )
        for ((code, expected) in cases) {
            NextcloudFixture { code to "" }.use { fixture ->
                assertEquals("HTTP $code", expected, repository().pollLogin(session(fixture.origin)))
                val poll = fixture.requests.single()
                assertEquals("POST" to "/poll", poll.method to poll.target)
                assertEquals("token=token", poll.body)
            }
        }
    }

    @Test fun `unreadable poll answer fails and a lost connection may be retried`() = runBlocking {
        NextcloudFixture { 200 to "{}" }.use { fixture ->
            assertEquals(LoginPollResult.Failed(LoginPollFailure.Unknown), repository().pollLogin(session(fixture.origin)))
        }
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val transport = NextcloudTransport(Json, client)
        val repository = NextcloudLoginRepository(NextcloudAuthClient(transport), transport)
        assertEquals(
            LoginPollResult.Failed(LoginPollFailure.Network, isRecoverable = true),
            repository.pollLogin(session("https://cloud.example")),
        )
    }

    @Test fun `missing uid fails the login instead of polling a spent token`() = runBlocking {
        NextcloudFixture { request ->
            if (request.target == "/poll") 200 to credentialsJson(request) else 500 to ""
        }.use { fixture ->
            val result = repository().pollLogin(session(fixture.origin))
            assertEquals(LoginPollResult.Failed(LoginPollFailure.Account), result)
        }
    }

    @Test fun `describe uses authenticated request and distinguishes unavailable from errors`() = runBlocking {
        val cases = listOf(
            Triple(200, """{"version":"10.1.0","baseUrl":"/","loginFlowUrl":"/","uid":"test"}""", MemoriesCompatibility.Supported),
            Triple(200, """{"version":"8.1.0","baseUrl":"/","loginFlowUrl":"/"}""", MemoriesCompatibility.TooOld("8.1.0")),
            Triple(200, "{}", MemoriesCompatibility.UnknownVersion),
            Triple(200, "<html>Login</html>", MemoriesCompatibility.UnknownVersion),
            Triple(404, "", MemoriesCompatibility.Unavailable),
            Triple(410, "", MemoriesCompatibility.Unavailable),
            Triple(401, "", MemoriesCompatibility.CheckFailed),
            Triple(403, "", MemoriesCompatibility.CheckFailed),
            Triple(500, "", MemoriesCompatibility.CheckFailed),
        )
        for ((code, body, expected) in cases) {
            NextcloudFixture { code to body }.use { fixture ->
                val credentials = com.syrok0010.nextgallery.core.session.AccountCredentials(fixture.origin, "test", "password", "test")
                assertEquals(expected, repository().checkMemories(credentials))
                val request = fixture.requests.single()
                assertEquals("/apps/memories/api/describe", request.target)
                assertEquals(Credentials.basic("test", "password"), request.headers["authorization"])
            }
        }
    }

    @Test fun `connection failure is not an unsupported version`() = runBlocking {
        val client = okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline") }.build()
        val transport = NextcloudTransport(Json, client)
        val repository = NextcloudLoginRepository(NextcloudAuthClient(transport), transport)
        val credentials = com.syrok0010.nextgallery.core.session.AccountCredentials("https://cloud.example", "test", "password", "test")
        assertEquals(MemoriesCompatibility.NetworkError, repository.checkMemories(credentials))
    }

    private fun repository(): NextcloudLoginRepository {
        val transport = NextcloudTransport(Json { ignoreUnknownKeys = true })
        return NextcloudLoginRepository(NextcloudAuthClient(transport), transport)
    }

    private fun session(origin: String) = LoginSession("$origin/", "$origin/login", "$origin/poll", "token")

    private fun credentialsJson(request: Recorded): String {
        val origin = "http://${request.headers.getValue("host")}"
        return """{"server":"$origin","loginName":"anna@example.com","appPassword":"app-password"}"""
    }
}
