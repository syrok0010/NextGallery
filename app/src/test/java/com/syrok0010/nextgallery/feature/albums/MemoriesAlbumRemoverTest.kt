package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MemoriesAlbumRemoverTest {
    @Test fun `removal lists membership once and only deletes selected album links`() = runBlocking {
        NextcloudFixture { request ->
            when (request.method) {
                "PROPFIND" -> 207 to listing("/remote.php/dav/photos/anna/albums/Лето & море")
                "DELETE" -> (if (request.decodedTarget.endsWith("/2-b.jpg")) 403 else 204) to ""
                else -> error("Unexpected file operation: $request")
            }
        }.use { fixture ->
            assertEquals(
                AlbumRemovalResult(2, 1),
                fixture.writer.remove(
                    AlbumAddress("anna", "Лето & море"),
                    setOf(1, 2, 99),
                ).summary,
            )
            val list = fixture.requests.single { it.method == "PROPFIND" }
            assertEquals("1", list.headers["depth"])
            val deletes = fixture.requests.filter { it.method == "DELETE" }
            assertEquals(
                setOf(
                    "/remote.php/dav/photos/anna/albums/Лето & море/1-a.jpg",
                    "/remote.php/dav/photos/anna/albums/Лето & море/2-b.jpg",
                ),
                deletes.map { it.decodedTarget }.toSet(),
            )
            assertTrue(fixture.requests.none { "/dav/files/" in it.target || "/image/info/" in it.target })
        }
    }

    @Test fun `parses captured Photos membership response including root propstat 404`() = runBlocking {
        val xml = requireNotNull(javaClass.getResource("/albums/membership-propfind.xml")).readText()
        NextcloudFixture { request ->
            when (request.method) {
                "PROPFIND" -> 207 to xml
                "DELETE" -> 204 to ""
                else -> error("Unexpected request: $request")
            }
        }.use { fixture ->
            val credentials = AccountCredentials(fixture.origin, "test", "secret", "test")
            val albums = MemoriesAlbums(
                credentials,
                MemoriesApiClient(fixture.transport) { credentials },
                PhotosAlbumsDavClient(fixture.transport) { credentials },
                FilesDavClient(fixture.transport) { credentials },
                InMemoryMediaIdentityRegistry(),
            )
            assertEquals(
                AlbumRemovalResult(1, 0),
                albums.remove(AlbumAddress("test", "ng133-1793518885-1"), setOf(305)).summary,
            )
            assertEquals(
                listOf("/remote.php/dav/photos/test/albums/ng133-1793518885-1/305-photo.jpg"),
                fixture.requests.filter { it.method == "DELETE" }.map { it.target },
            )
        }
    }

    @Test fun `listing error never attempts a delete or treats selected files as absent`() = runBlocking {
        NextcloudFixture { 403 to "" }.use { fixture ->
            try {
                fixture.writer.remove(AlbumAddress("anna", "album"), setOf(1))
                fail("Expected failed listing")
            } catch (_: IOException) { }
            assertEquals(listOf("PROPFIND"), fixture.requests.map { it.method })
        }
    }

    @Test fun `foreign album fails before issuing HTTP`() = runBlocking {
        NextcloudFixture { error("Must not request foreign album") }.use { fixture ->
            try {
                fixture.writer.remove(AlbumAddress("someone", "album"), setOf(1))
                fail("Expected rejected foreign album")
            } catch (_: IllegalArgumentException) { }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `DAV hrefs and DOCTYPE cannot escape album origin collection or file identity`() = runBlocking {
        val rootPath = "/remote.php/dav/photos/anna/albums/album"
        val valid = listing(rootPath)
        val href = "$rootPath/1-a.jpg"
        val hostileBodies = listOf(
            valid.replace(href, "https://attacker.example/remote.php/dav/photos/anna/albums/album/1-a.jpg"),
            valid.replace(href, "/remote.php/dav/files/anna/Photos/1-a.jpg"),
            valid.replace(href, "$rootPath/nested/1-a.jpg"),
            valid.replace(href, "$rootPath/2-a.jpg"),
            valid.replace(href, "$rootPath/1-a.jpg?x=1"),
            "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>$valid",
        )
        hostileBodies.forEach { body ->
            NextcloudFixture { request ->
                when (request.method) {
                    "PROPFIND" -> 207 to body
                    else -> error("Must not issue ${request.method} for hostile listing")
                }
            }.use { fixture ->
                try {
                    fixture.writer.remove(AlbumAddress("anna", "album"), setOf(1))
                    fail("Accepted unsafe listing")
                } catch (_: IOException) { }
                assertEquals(listOf("PROPFIND"), fixture.requests.map { it.method })
            }
        }
    }

    @Test fun `redirects on delete are never followed`() = runBlocking {
        NextcloudFixture { request ->
            when (request.method) {
                "PROPFIND" -> 207 to listing("/remote.php/dav/photos/anna/albums/album")
                "DELETE" -> 302 to ""
                else -> error("Unexpected request: $request")
            }
        }.use { fixture ->
            assertEquals(
                AlbumRemovalResult(0, 1),
                fixture.writer.remove(AlbumAddress("anna", "album"), setOf(1)).summary,
            )
            assertEquals(listOf("PROPFIND", "DELETE"), fixture.requests.map { it.method })
        }
    }

    companion object {
        fun listing(path: String): String {
            val encoded = path.replace("&", "&amp;")
            return """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:href>$encoded/</d:href><d:propstat><d:prop><oc:fileid/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>
              ${(1..3).joinToString("") { id -> """<d:response><d:href>$encoded/$id-${('a'.code + id - 1).toChar()}.jpg</d:href><d:propstat><d:prop><oc:fileid>$id</oc:fileid></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>""" }}
              </d:multistatus>"""
        }
    }
}
