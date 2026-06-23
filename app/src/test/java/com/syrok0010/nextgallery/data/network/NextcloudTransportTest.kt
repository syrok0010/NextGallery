package com.syrok0010.nextgallery.data.network

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import kotlinx.serialization.json.Json
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Test

class NextcloudTransportTest {
    private val transport = NextcloudTransport(Json)
    private val credentials = AccountCredentials(
        serverUrl = "cloud.example.com",
        loginName = "user",
        appPassword = "secret",
    )

    @Test
    fun `normalize server origin adds scheme and trims trailing slash`() {
        assertEquals(
            "https://cloud.example.com",
            NextcloudTransport.normalizeServerOrigin(" cloud.example.com/ "),
        )
        assertEquals(
            "http://cloud.example.com/path",
            NextcloudTransport.normalizeServerOrigin("http://cloud.example.com/path///"),
        )
    }

    @Test
    fun `authenticated request builder defaults to json policy`() {
        val request = transport.authenticatedRequestBuilder(
            credentials = credentials,
            url = "https://cloud.example.com/apps/memories/api/days",
        ).get().build()

        assertAuthenticatedHeaders(request)
        assertEquals("application/json", request.header("Accept"))
    }

    @Test
    fun `authenticated request builder supports binary accept override`() {
        val request = transport.authenticatedRequestBuilder(
            credentials = credentials,
            url = "https://cloud.example.com/apps/memories/api/image/multipreview",
            accept = "application/octet-stream",
        ).post("{}".toRequestBody()).build()

        assertAuthenticatedHeaders(request)
        assertEquals("application/octet-stream", request.header("Accept"))
    }

    @Test
    fun `authorization header uses basic auth encoding`() {
        assertEquals(
            "Basic dXNlcjpzZWNyZXQ=",
            NextcloudTransport.authorizationHeader(credentials),
        )
    }

    private fun assertAuthenticatedHeaders(request: Request) {
        assertEquals("Basic dXNlcjpzZWNyZXQ=", request.header("Authorization"))
        assertEquals("XMLHttpRequest", request.header("X-Requested-With"))
        assertEquals("true", request.header("OCS-APIRequest"))
    }
}
