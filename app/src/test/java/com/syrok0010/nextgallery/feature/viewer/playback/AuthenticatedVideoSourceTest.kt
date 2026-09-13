package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import java.io.IOException
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
        var account = AccountCredentials("https://cloud.example/nextcloud/", "alice", "first")
        val requests = mutableListOf<Request>()
        val transport = NextcloudTransport(Json, OkHttpClient())
        val client = transport.baseClient.newBuilder()
            .addInterceptor(AuthenticatedVideoSource(transport) { account })
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(206).message("Partial Content").body("video".toResponseBody()).build()
            }.build()
        val reference = VideoSources.from(MediaAssetRef.MemoriesFile(42), account.serverUrl).primary
        fun request() = client.newCall(Request.Builder().url(reference).header("Range", "bytes=128-255").build()).execute()
        request().use { assertEquals(206, it.code) }
        account = account.copy(appPassword = "second")
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
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url("https://other.example/original/42").build()).execute().close()
        }
    }

    @Test fun `HLS playlists and segments retain real URLs and authenticate each request`() {
        var account = AccountCredentials("https://cloud.example/nextcloud", "alice", "first")
        val requests = mutableListOf<Request>()
        val transport = NextcloudTransport(Json, OkHttpClient())
        val client = transport.baseClient.newBuilder()
            .addInterceptor(AuthenticatedVideoSource(transport) { account })
            .addInterceptor { chain ->
                requests += chain.request()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("fixture".toResponseBody()).build()
            }.build()
        for (profile in listOf("index.m3u8", "360p.m3u8", "360p-00001.ts")) {
            val realUrl = "https://cloud.example/nextcloud/apps/memories/api/video/transcode/client123/42/$profile?session=abc"
            client.newCall(Request.Builder().url(realUrl).build()).execute().use {
                assertEquals(realUrl, it.request.url.toString())
            }
            account = account.copy(appPassword = "second")
        }
        assertEquals("/nextcloud/apps/memories/api/video/transcode/client123/42/360p-00001.ts", requests.last().url.encodedPath)
        assertEquals("session=abc", requests.last().url.encodedQuery)
        assertEquals(Credentials.basic("alice", "first"), requests.first().header("Authorization"))
        assertEquals(Credentials.basic("alice", "second"), requests.last().header("Authorization"))
    }

    @Test fun `foreign origins subpaths and old account URLs never receive credentials`() {
        var account = AccountCredentials("https://cloud.example/nextcloud", "alice", "secret")
        var requests = 0
        val transport = NextcloudTransport(Json, OkHttpClient())
        val client = transport.baseClient.newBuilder()
            .addInterceptor(AuthenticatedVideoSource(transport) { account })
            .addInterceptor { chain ->
                requests++
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body("video".toResponseBody()).build()
            }.build()
        for (url in listOf(
            "https://evil.example/nextcloud/apps/memories/api/stream/42",
            "http://cloud.example/nextcloud/apps/memories/api/stream/42",
            "https://cloud.example:8443/nextcloud/apps/memories/api/stream/42",
            "https://user@cloud.example/nextcloud/apps/memories/api/stream/42",
            "https://cloud.example/other/apps/memories/api/stream/42",
            "https://cloud.example/nextcloud/apps/memories/api/../login",
        )) {
            assertThrows(url, IOException::class.java) {
                client.newCall(Request.Builder().url(url).build()).execute().close()
            }
        }
        val oldUrl = RemoteVideoOriginal(42, account.serverUrl).uri
        account = account.copy(serverUrl = "https://new.example")
        assertThrows(IOException::class.java) {
            client.newCall(Request.Builder().url(oldUrl).build()).execute().close()
        }
        assertEquals(0, requests)
    }

    @Test fun `source plan prefers local and exposes only one remote fallback`() {
        val local = MediaAssetRef.LocalContent("content://media/42", null)
        val remote = MediaAssetRef.MemoriesFile(42)
        assertEquals(VideoSources(local.contentUri), VideoSources.from(local))
        assertEquals(VideoSources(local.contentUri, VideoSources.from(remote, "https://cloud.example").primary, RemoteVideoOriginal(42, "https://cloud.example")), VideoSources.from(MediaAssetRef.LocalFirst(local, remote), "https://cloud.example"))
    }
}
