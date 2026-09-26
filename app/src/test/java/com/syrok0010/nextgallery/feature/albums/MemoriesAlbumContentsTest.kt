package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class MemoriesAlbumContentsTest {
    @Test fun `all batches carry encoded cluster filter and use media identity`() = runBlocking {
        Fixture().use { fixture ->
            val batches = fixture.load()
            assertEquals(2, batches.last().items.size)
            assertEquals(3, batches.last().loaded)
            assertEquals(listOf(3L, 2L), batches.last().items.map { it.remoteFileId })
            assertEquals(2, batches.last().items.map { it.mediaId }.toSet().size)
            assertEquals(3, batches.last().total)
            assertEquals(2, fixture.requests.size)
            fixture.requests.forEach { request ->
                assertTrue(URLDecoder.decode(request.first(), "UTF-8").contains("albums=anna/Лето & море"))
                assertTrue(request.any { it == "Authorization: ${Credentials.basic("fixture", "password")}" })
            }
            assertTrue(fixture.requests.last().first().contains("/days/20?"))
        }
    }
    @Test fun `failed day request propagates failure after initial progress`() = runBlocking {
        Fixture(fail = true).use { fixture ->
            try { fixture.load(); fail("Expected HTTP failure") }
            catch (error: HttpException) { assertEquals(503, error.code()) }
        }
    }
    private class Fixture(val fail: Boolean = false) : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        val requests = CopyOnWriteArrayList<List<String>>()
        init { executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    requests += lines
                    val index = lines.first().contains("/days?")
                    val body = if (index) """[{"dayid":20,"count":3,"detail":[{"fileid":1,"dayid":20,"auid":"same"}]}]"""
                        else """[{"fileid":1,"dayid":20,"auid":"same"},{"fileid":2,"dayid":20,"auid":"same"},{"fileid":3,"dayid":20,"isvideo":1}]"""
                    val bytes = body.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 ${if (fail && !index) 503 else 200} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        } }
        suspend fun load() = MemoriesAlbumContents(NextcloudTransport(Json { ignoreUnknownKeys = true }), InMemoryMediaIdentityRegistry()).load(
            AlbumLocation.Remote("anna/Лето & море"),
            AccountCredentials("http://127.0.0.1:${server.localPort}/nextcloud", "fixture", "password"),
        ).toList()
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
