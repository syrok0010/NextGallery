package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException

class MemoriesAlbumSourceTest {
    @Test fun `authenticated list supports numeric strings and respects server hidden setting`() =
        runBlocking {
            Fixture(
                """{"version":"7.6.1","albums_enabled":true,"show_hidden_albums":false,"timeline_path":"/Photos"}""",
                """[{"album_id":1,"name":"Отпуск","count":"7","user_display":"Анна"},{"album_id":2,"name":".hidden","count":0}]""",
            ).use { fixture ->
                val result = fixture.load()
                assertTrue(result.supported)
                assertEquals(listOf("Отпуск"), result.albums.map { it.name })
                assertEquals(7, result.albums.first().count)
                assertEquals(
                    listOf(
                        "GET /nextcloud/apps/memories/api/config",
                        "GET /nextcloud/apps/memories/api/clusters/albums",
                    ),
                    fixture.requests.map { "${it.method} ${it.target}" },
                )
                assertTrue(
                    fixture.requests.all {
                        it.headers["authorization"] == Credentials.basic("fixture", "password")
                    },
                )
            }
        }

    @Test fun `disabled feature does not request album list`() =
        runBlocking {
            Fixture("""{"version":"7.6.1","albums_enabled":false}""", "[]").use {
                assertFalse(it.load().supported)
                assertEquals(1, it.requests.size)
            }
        }

    @Test fun `http failure is not a successful empty catalog`() =
        runBlocking {
            Fixture("""{"version":"7.6.1","albums_enabled":true}""", "[]", 503).use {
                try {
                    it.load()
                    fail("Expected an HTTP error")
                } catch (
                    error: HttpException,
                ) {
                    assertEquals(503, error.code())
                }
            }
        }
    private class Fixture(config: String, albums: String, code: Int = 200) : AutoCloseable {
        private val server = NextcloudFixture { request ->
            if (request.target.endsWith("/config")) 200 to config else code to albums
        }
        val requests get() = server.requests
        private val credentials =
            AccountCredentials("${server.origin}/nextcloud", "fixture", "password", "fixture")

        suspend fun load() =
            MemoriesAlbumSource(
                MemoriesApiClient(server.transport) { credentials },
            ).load(credentials)

        override fun close() {
            server.close()
        }
    }
}
