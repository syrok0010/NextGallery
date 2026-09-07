package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class AuthenticatedVideoSourceTest {
    @Test fun `every request resolves current credentials and retains ranges without URL secrets`() {
        var account: AccountCredentials? = AccountCredentials("https://cloud.example/nextcloud/", "alice", "first")
        val requests = mutableListOf<Request>()
        val transport = NextcloudTransport(Json, OkHttpClient())
        val client = transport.baseClient.newBuilder()
            .addInterceptor(AuthenticatedVideoSource(transport) { account })
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content").body("video".toResponseBody()).build()
            }.build()
        val reference = VideoSources.from(MediaAssetRef.MemoriesFile(42)).primary
        fun request() = client.newCall(Request.Builder().url(reference).header("Range", "bytes=128-255").build()).execute()
        request().use { assertEquals(206, it.code) }
        account = account!!.copy(appPassword = "second")
        request().close()
        assertEquals(2, requests.size)
        requests.forEach {
            assertEquals("https://cloud.example/nextcloud/apps/memories/api/stream/42", it.url.toString())
            assertEquals("bytes=128-255", it.header("Range"))
            assertEquals("XMLHttpRequest", it.header("X-Requested-With"))
            assertEquals("true", it.header("OCS-APIRequest"))
            assertEquals("*/*", it.header("Accept"))
        }
        assertEquals(Credentials.basic("alice", "first"), requests[0].header("Authorization"))
        assertEquals(Credentials.basic("alice", "second"), requests[1].header("Authorization"))
        account = null
        assertThrows(VideoAuthenticationRequired::class.java) { request().close() }
        assertEquals(2, requests.size)
        assertThrows(java.io.IOException::class.java) {
            client.newCall(Request.Builder().url("https://other.example/original/42").build()).execute().close()
        }
    }

    @Test fun `source plan prefers local and exposes only one remote fallback`() {
        val local = MediaAssetRef.LocalContent("content://media/42", null)
        val remote = MediaAssetRef.MemoriesFile(42)
        assertEquals(VideoSources(local.contentUri), VideoSources.from(local))
        assertEquals(VideoSources(local.contentUri, VideoSources.from(remote).primary), VideoSources.from(MediaAssetRef.LocalFirst(local, remote)))
    }
}
