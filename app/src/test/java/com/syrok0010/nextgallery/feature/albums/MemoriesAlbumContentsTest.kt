package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.URLDecoder
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class MemoriesAlbumContentsTest {
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
                assertTrue(URLDecoder.decode(request.target, "UTF-8").contains("albums=anna/Лето & море"))
                assertEquals(Credentials.basic("fixture", "password"), request.headers["authorization"])
            }
            assertTrue(fixture.requests.last().target.contains("/days/20?"))
        }
    }
    @Test fun `failed day request propagates failure after initial progress`() = runBlocking {
        Fixture(fail = true).use { fixture ->
            try { fixture.load(); fail("Expected HTTP failure") }
            catch (error: HttpException) { assertEquals(503, error.code()) }
        }
    }
    private class Fixture(fail: Boolean = false) : AutoCloseable {
        private val server = NextcloudFixture { request ->
            if (request.target.contains("/days?")) {
                200 to """[{"dayid":20,"count":3,"detail":[{"fileid":1,"dayid":20,"epoch":1716768000,"size":2048}]}]"""
            } else {
                val day = """[{"fileid":1,"dayid":20,"epoch":1716768000,"size":2048},{"fileid":2,"dayid":20,"epoch":1716768000,"size":2048},{"fileid":3,"dayid":20,"epoch":1716768000,"isvideo":1}]"""
                (if (fail) 503 else 200) to day
            }
        }
        val requests get() = server.requests
        private val credentials =
            AccountCredentials("${server.origin}/nextcloud", "fixture", "password", "fixture")

        suspend fun load() = MemoriesAlbumContents(
            MemoriesApiClient(server.transport) { credentials },
            InMemoryMediaIdentityRegistry(),
        ).load(AlbumLocation.Remote("anna/Лето & море")).toList()

        override fun close() {
            server.close()
        }
    }
}
