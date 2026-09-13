package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MemoriesAlbumSourceTest {
    @Test fun `authenticated list supports numeric strings and respects server hidden setting`() = runBlocking {
        Fixture("""{"albums_enabled":true,"show_hidden_albums":false}""",
            """[{"album_id":1,"name":"Отпуск","count":"7","user_display":"Анна"},{"album_id":2,"name":".hidden","count":0}]""").use { fixture ->
            val result = fixture.load()
            assertTrue(result.supported)
            assertEquals(listOf("Отпуск"), result.albums.map { it.name })
            assertEquals(7, result.albums.first().count)
            assertEquals(listOf("GET /nextcloud/apps/memories/api/config HTTP/1.1", "GET /nextcloud/apps/memories/api/clusters/albums HTTP/1.1"), fixture.requests.map { it.first() })
            assertTrue(fixture.requests.all { lines -> lines.any { it == "Authorization: ${Credentials.basic("fixture", "password")}" } })
        }
    }
    @Test fun `disabled feature does not request album list`() = runBlocking {
        Fixture("""{"albums_enabled":0}""", "[]").use {
            assertFalse(it.load().supported)
            assertEquals(1, it.requests.size)
        }
    }
    @Test fun `http failure is not a successful empty catalog`() = runBlocking {
        Fixture("""{"albums_enabled":1}""", "[]", 503).use {
            try { it.load(); fail("Expected an HTTP error") }
            catch (error: retrofit2.HttpException) { assertEquals(503, error.code()) }
        }
    }
    private class Fixture(val config: String, val albums: String, val code: Int = 200) : AutoCloseable {
        val server = ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())
        val executor = Executors.newSingleThreadExecutor()
        val requests = CopyOnWriteArrayList<List<String>>()
        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try { server.accept() } catch (_: java.io.IOException) { break }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        requests += lines
                        val isConfig = lines.first().contains("/config ")
                        val bytes = (if (isConfig) config else albums).toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 ${if (isConfig) 200 else code} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            }
        }
        suspend fun load() = MemoriesAlbumSource(NextcloudTransport(Json)).load(
            AccountCredentials("http://127.0.0.1:${server.localPort}/nextcloud", "fixture", "password"))
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
