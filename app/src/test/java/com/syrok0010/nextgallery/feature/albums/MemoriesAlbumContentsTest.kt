package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata
import com.syrok0010.nextgallery.feature.library.favoriteProjection
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
    @Test fun `fresh album read accepts external favorite and persists it without timeline metadata`() = runBlocking {
        Fixture().use { fixture ->
            val item = fixture.load().last().items.first { it.remoteFileId == 1L }
            fixture.metadata.confirm(fixture.metadata.begin(fixture.credentials), item, true) { fixture.persist(listOf(it)) }
            assertTrue(favoriteProjection(listOf(item), fixture.metadata.values.value).single().isFavorite)
            val refreshed = fixture.load().last().items.first { it.remoteFileId == 1L }
            assertFalse(favoriteProjection(listOf(refreshed), fixture.metadata.values.value).single().isFavorite)
            assertFalse(fixture.persisted.getValue(1).isFavorite)
        }
    }
    @Test fun `album response begun before favorite confirmation cannot undo it in metadata or cache`() = runBlocking {
        Fixture().use { fixture ->
            val item = fixture.load().last().items.first { it.remoteFileId == 1L }
            fixture.beforeIndexResponse = {
                runBlocking {
                    fixture.metadata.confirm(fixture.metadata.begin(fixture.credentials), item, true) { fixture.persist(listOf(it)) }
                }
            }
            val stale = fixture.load().last().items.first { it.remoteFileId == 1L }
            assertTrue(stale.isFavorite)
            assertTrue(fixture.persisted.getValue(1).isFavorite)
        }
    }
    @Test fun `all batches carry encoded cluster filter and use media identity`() = runBlocking {
        Fixture().use { fixture ->
            val batches = fixture.load()
            // Two server files with one AUID stay two objects.
            assertEquals(3, batches.last().items.size)
            assertEquals(3, batches.last().loaded)
            assertEquals(listOf(3L, 2L, 1L), batches.last().items.map { it.remoteFileId })
            assertEquals(3, batches.last().items.map { it.mediaId }.toSet().size)
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
        val credentials = AccountCredentials("http://127.0.0.1:${server.localPort}/nextcloud", "fixture", "password", "fixture")
        val metadata = CloudFavoriteMetadata(SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        }))
        val persisted = mutableMapOf<Long, MediaItem>()
        fun persist(items: List<MediaItem>) { items.forEach { persisted[requireNotNull(it.remoteFileId)] = it } }
        var beforeIndexResponse: () -> Unit = { }
        init { executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                socket.use {
                    val reader = it.getInputStream().bufferedReader()
                    val lines = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                    requests += lines
                    val index = lines.first().contains("/days?")
                    if (index) beforeIndexResponse()
                    val body = if (index) """[{"dayid":20,"count":3,"detail":[{"fileid":1,"dayid":20,"epoch":1716768000,"size":2048}]}]"""
                        else """[{"fileid":1,"dayid":20,"epoch":1716768000,"size":2048},{"fileid":2,"dayid":20,"epoch":1716768000,"size":2048},{"fileid":3,"dayid":20,"epoch":1716768000,"isvideo":1}]"""
                    val bytes = body.toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 ${if (fail && !index) 503 else 200} Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes); flush()
                    }
                }
            }
        } }
        suspend fun load() = MemoriesAlbumContents(NextcloudTransport(Json { ignoreUnknownKeys = true }), InMemoryMediaIdentityRegistry(), metadata, ::persist).load(
            AlbumLocation.Remote("anna/Лето & море"),
            credentials,
        ).toList()
        override fun close() { server.close(); executor.shutdownNow() }
    }
}
