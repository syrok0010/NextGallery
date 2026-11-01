package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.fileUrl
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesFiles
import java.io.File
import java.net.Proxy
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in live contract: only task-owned objects on the shared test server, never user data. */
class LiveAlbumRemovalContractTest {
    @Test fun `membership DELETE preserves original timeline and second album`() = runBlocking {
        assumeTrue(System.getenv("NG133_LIVE_CONTRACT") == "1")
        val credentials = AccountCredentials(
            "http://test.syrok", requireNotNull(System.getenv("NG133_TEST_LOGIN")),
            requireNotNull(System.getenv("NG133_TEST_PASSWORD")), userId = "test",
        )
        val transport = NextcloudTransport(
            Json { ignoreUnknownKeys = true },
            NextcloudTransport.defaultBaseClient().newBuilder().proxy(Proxy.NO_PROXY).build(),
        )
        val writer = MemoriesAlbumWriter(transport, MemoriesFiles(transport))
        val remover = MemoriesAlbumRemover(transport)
        val prefix = "ng133-contract-" + UUID.randomUUID().toString().take(8)
        val folder = fileUrl(credentials.serverUrl, credentials.userId, "/Photos/$prefix")
        val original = fileUrl(credentials.serverUrl, credentials.userId, "/Photos/$prefix/photo.jpg")
        val locations = listOf(AlbumLocation.Remote("test/$prefix-a"), AlbumLocation.Remote("test/$prefix-b"))
        val evidence = mutableListOf<String>()
        fun request(method: String, url: HttpUrl, body: ByteArray? = null): Pair<Int, String> {
            val request = transport.authenticatedRequestBuilder(credentials, url.toString(), "*/*")
                .method(method, body?.toRequestBody("application/octet-stream".toMediaType())).build()
            return transport.baseClient.newCall(request).execute().use {
                evidence += "$method ${url.encodedPath}: ${it.code}"
                it.code to if (method == "HEAD") "" else it.body.string()
            }
        }
        try {
            val fixture = transport.authenticatedRequestBuilder(
                credentials, fileUrl(credentials.serverUrl, "test", "/Photos/fixtures/cloud-01.jpg").toString(), "*/*",
            ).get().build()
            val bytes = transport.baseClient.newCall(fixture).execute().use {
                assertEquals(200, it.code)
                it.body.bytes()
            }
            assertEquals(201, request("MKCOL", folder).first)
            val upload = transport.authenticatedRequestBuilder(credentials, original.toString(), "*/*")
                .header("If-None-Match", "*").put(bytes.toRequestBody("image/jpeg".toMediaType())).build()
            val fileId = transport.baseClient.newCall(upload).execute().use {
                assertEquals(201, it.code)
                requireNotNull(it.header("OC-FileId")).takeWhile(Char::isDigit).toLong()
            }
            for (location in locations) {
                writer.create(credentials, requireNotNull(AlbumAddress.of(location)).name)
                assertEquals(AlbumCopyOutcome.Added, writer.addFile(credentials, original, requireNotNull(AlbumAddress.of(location))))
            }
            val reindex = original.newBuilder().encodedPath("/apps/memories/api/image/reindex/$fileId").build()
            assertEquals(200, request("POST", reindex, byteArrayOf()).first)
            assertEquals(AlbumRemovalResult(1, 0), remover.remove(credentials, locations[0], setOf(fileId)))
            evidence += "Production MemoriesAlbumRemover: removed=1, failed=0"
            assertEquals(200, request("HEAD", original).first)
            val info = original.newBuilder().encodedPath("/apps/memories/api/image/info/$fileId").build()
            val (infoCode, infoBody) = request("GET", info)
            assertEquals(200, infoCode)
            assertTrue(Json.parseToJsonElement(infoBody).jsonObject.getValue("intimeline").jsonPrimitive.boolean)
            val api = transport.retrofit(transport.normalizeBaseUrl(credentials.serverUrl), transport.authenticatedClient(credentials))
                .create(AlbumContentsApi::class.java)
            assertTrue(api.days(locations[0].clusterId).isEmpty())
            assertTrue(api.days(locations[1].clusterId).flatMap { it.detail }.any { it.fileid == fileId })
            evidence += "Memories: intimeline=true; removed album empty; second album contains $fileId"
            // Already absent is idempotent, without touching the original file.
            assertEquals(AlbumRemovalResult(1, 0), remover.remove(credentials, locations[0], setOf(fileId)))
            assertEquals(200, request("HEAD", original).first)
        } finally {
            for (location in locations) request("DELETE", albumUrl(credentials.serverUrl, requireNotNull(AlbumAddress.of(location))))
            request("DELETE", folder)
            // The deleted task folder is the only trash entry this test owns.
            val trash = original.newBuilder().encodedPath("/remote.php/dav/trashbin/test/trash").build()
            val propfind = transport.authenticatedRequestBuilder(credentials, trash.toString(), "application/xml")
                .method("PROPFIND", null).header("Depth", "1").build()
            transport.baseClient.newCall(propfind).execute().use { response ->
                if (response.code == 207) {
                    val xml = response.body.string()
                    val hrefs = Regex("<[^>]*:?href[^>]*>([^<]+)</[^>]*:?href>").findAll(xml).map { it.groupValues[1] }
                    for (href in hrefs) {
                        val url = trash.resolve(href) ?: continue
                        val name = url.pathSegments.lastOrNull { it.isNotEmpty() }.orEmpty()
                        if (url.host == trash.host && url.encodedPath.startsWith(trash.encodedPath + "/") && name.startsWith("$prefix.d")) {
                            request("DELETE", url)
                        }
                    }
                }
            }
            File("build/outputs/album-contract/contract.txt").apply { parentFile?.mkdirs() }.writeText(evidence.joinToString("\n") + "\n")
        }
    }
}
