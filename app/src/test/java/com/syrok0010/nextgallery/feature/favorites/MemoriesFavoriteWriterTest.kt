package com.syrok0010.nextgallery.feature.favorites

import com.sun.net.httpserver.HttpServer
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

internal class MemoriesFavoriteWriterTest {
    private val url = "https://cloud.test/remote.php/dav/files/test/Photos/a.jpg".toHttpUrl()
    private fun xml(property: String = "favorite", code: Int = 200, href: String = url.encodedPath, value: String = "") =
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>$href</d:href><d:propstat><d:prop><oc:$property>$value</oc:$property></d:prop><d:status>HTTP/1.1 $code Status</d:status></d:propstat></d:response></d:multistatus>"""

    @Test fun `207 requires exact resource namespace property and successful propstat`() {
        assertEquals("", davProperty(xml(), url, "favorite"))
        assertNull(davProperty(xml(code = 403), url, "favorite"))
        assertNull(davProperty(xml(property = "fileid"), url, "favorite"))
        assertNull(davProperty(xml(href = "https://other.test${url.encodedPath}"), url, "favorite"))
        assertNull(davProperty(xml().replace("http://owncloud.org/ns", "urn:wrong"), url, "favorite"))
        val mixed = xml().replace("</d:response>", "<d:propstat><d:prop><oc:favorite/></d:prop><d:status>HTTP/1.1 424 Failed</d:status></d:propstat></d:response>")
        assertNull(davProperty(mixed, url, "favorite"))
    }

    @Test fun `XML entity declarations are rejected`() {
        try { davProperty("<!DOCTYPE d [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + xml(), url, "favorite"); fail("Entity accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun `writer verifies id owner encoded path and If-Match then checks propstat`() = runBlocking {
        val calls = mutableListOf<String>()
        var writeStatus = 200
        val path = "/remote.php/dav/files/test/Photos/%D0%A4%D0%BE%D1%82%D0%BE%20%26%201.jpg"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            calls += exchange.requestMethod + " " + exchange.requestURI.rawPath
            val body = when (exchange.requestMethod) {
                "GET" -> """{"fileid":7,"filename":"/Photos/Фото & 1.jpg","basename":"Фото & 1.jpg","owneruid":"test","etag":"abc","permissions":"RUDS"}"""
                "PROPFIND" -> xml("fileid", href = path.replace("&", "&amp;"), value = "7")
                else -> {
                    assertEquals("\"abc\"", exchange.requestHeaders.getFirst("If-Match"))
                    assertTrue(exchange.requestBody.readBytes().decodeToString().contains("<oc:favorite>1</oc:favorite>"))
                    xml(code = writeStatus, href = path.replace("&", "&amp;"))
                }
            }
            exchange.sendResponseHeaders(if (exchange.requestMethod == "GET") 200 else 207, body.toByteArray().size.toLong())
            exchange.responseBody.use { it.write(body.toByteArray()) }
        }
        server.start()
        try {
            val writer = MemoriesFavoriteWriter(NextcloudTransport(Json { ignoreUnknownKeys = true }))
            val creds = AccountCredentials("http://127.0.0.1:${server.address.port}", "test", "secret", "test")
            writer.set(creds, 7, true)
            assertEquals(listOf("GET /apps/memories/api/image/info/7", "PROPFIND ${path.replace("%26", "&")}", "PROPPATCH ${path.replace("%26", "&")}"), calls)
            writeStatus = 403
            try { writer.set(creds, 7, true); fail("Failed propstat accepted") } catch (_: java.io.IOException) { }
        } finally { server.stop(0) }
    }
    @Test fun `unverified owner id path permission and redirect never reach a writer`() = runBlocking {
        var mode = "owner"
        var otherCalls = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            if (!exchange.requestURI.path.startsWith("/apps/memories/api/image/info/")) otherCalls++
            if (mode == "redirect") {
                exchange.responseHeaders.set("Location", "/leak")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            } else {
                val owner = if (mode == "owner") "someone-else" else "test"
                val id = if (mode == "id") 99 else 7
                val path = if (mode == "path") "/Photos/../file.jpg" else "/Photos/file.jpg"
                val permissions = if (mode == "readonly") "R" else "RUDS"
                val body = """{"fileid":$id,"filename":"$path","basename":"file.jpg","owneruid":"$owner","etag":"abc","permissions":"$permissions"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
        try {
            val writer = MemoriesFavoriteWriter(NextcloudTransport(Json { ignoreUnknownKeys = true }))
            val creds = AccountCredentials("http://127.0.0.1:${server.address.port}", "test", "secret", "test")
            for (scenario in listOf("owner", "id", "path", "readonly", "redirect")) {
                mode = scenario
                try { writer.set(creds, 7, true); fail("Unverified file accepted: $scenario") }
                catch (_: IllegalArgumentException) { }
                catch (_: retrofit2.HttpException) { }
            }
            assertEquals(0, otherCalls)
        } finally { server.stop(0) }
    }

}
