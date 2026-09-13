package com.syrok0010.nextgallery.feature.viewer.playback

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class MemoriesVideoDiscoveryTest {
    @Test fun `disabled VOD never requests transcode`() = runBlocking {
        val requests = mutableListOf<Request>()
        val discovery = discovery(requests, true)
        assertTrue(discovery.qualities("https://memories.invalid/original/42", "client123").isEmpty())
        assertEquals(listOf("/nextcloud/apps/memories/api/config"), requests.map { it.url.encodedPath })
    }

    @Test fun `config and manifest use authenticated subpath contract`() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = discovery(requests, false).qualities("https://memories.invalid/original/42", "client123")
        assertEquals(listOf("Auto", "360p"), result.map { it.label })
        assertEquals("/nextcloud/apps/memories/api/video/transcode/client123/42/index.m3u8", requests.last().url.encodedPath)
        requests.forEach { assertEquals(Credentials.basic("alice", "secret"), it.header("Authorization")) }
    }

    @Test fun `unavailable transcode surfaces failure`() {
        assertThrows(IOException::class.java) {
            runBlocking { discovery(mutableListOf(), false, 403).qualities("https://memories.invalid/original/42", "client123") }
        }
    }

    private fun discovery(requests: MutableList<Request>, disabled: Boolean, status: Int = 200): MemoriesVideoDiscovery {
        val transport = NextcloudTransport(Json, OkHttpClient())
        val client = transport.baseClient.newBuilder()
            .addInterceptor(AuthenticatedVideoSource(transport) { AccountCredentials("https://cloud.example/nextcloud", "alice", "secret") })
            .addInterceptor { chain ->
                val request = chain.request()
                requests += request
                val config = request.url.encodedPath.endsWith("/config")
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                    .code(if (config) 200 else status).message("fixture")
                    .body((if (config) """{"version":"7","vod_disable":$disabled}"""
                        else "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=100000,RESOLUTION=640x360\n360p.m3u8").toResponseBody()).build()
            }.build()
        return MemoriesVideoDiscovery(client)
    }
}
