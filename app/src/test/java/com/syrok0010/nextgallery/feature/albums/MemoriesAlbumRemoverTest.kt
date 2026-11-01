package com.syrok0010.nextgallery.feature.albums

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MemoriesAlbumRemoverTest {
    @Test fun `removal lists membership once and only deletes selected album links`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { request ->
            when (request.method) {
                "PROPFIND" -> 207 to listing("/remote.php/dav/photos/anna/albums/Лето & море")
                "DELETE" -> (if (request.decodedTarget.endsWith("/2-b.jpg")) 403 else 204) to ""
                else -> error("Unexpected file operation: $request")
            }
        }.use { fixture ->
            assertEquals(
                AlbumRemovalResult(2, 1),
                MemoriesAlbumRemover(fixture.writerTransport).remove(
                    fixture.credentials, AlbumLocation.Remote("anna/Лето & море"), setOf(1, 2, 99),
                ),
            )
            val list = fixture.requests.single { it.method == "PROPFIND" }
            assertEquals("1", list.headers["depth"])
            val deletes = fixture.requests.filter { it.method == "DELETE" }
            assertEquals(setOf("/remote.php/dav/photos/anna/albums/Лето & море/1-a.jpg", "/remote.php/dav/photos/anna/albums/Лето & море/2-b.jpg"), deletes.map { it.decodedTarget }.toSet())
            assertTrue(fixture.requests.none { "/dav/files/" in it.target || "/image/info/" in it.target })
        }
    }

    @Test fun `parses captured Photos membership response including root propstat 404`() {
        MemoriesAlbumWriterTest.Fixture { 500 to "" }.use { fixture ->
            val xml = requireNotNull(javaClass.getResource("/albums/membership-propfind.xml")).readText()
            val root = albumUrl(fixture.origin, AlbumAddress("test", "ng133-1793518885-1"))
            val member = albumMemberUrls(root, xml).getValue(305)
            assertEquals("/remote.php/dav/photos/test/albums/ng133-1793518885-1/305-photo.jpg", member.encodedPath)
        }
    }

    @Test fun `listing error never attempts a delete or treats selected files as absent`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { 403 to "" }.use { fixture ->
            try {
                MemoriesAlbumRemover(fixture.writerTransport).remove(fixture.credentials, AlbumLocation.Remote("anna/album"), setOf(1))
                fail("Expected failed listing")
            } catch (_: java.io.IOException) { }
            assertEquals(listOf("PROPFIND"), fixture.requests.map { it.method })
        }
    }

    @Test fun `foreign album fails before issuing HTTP`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { error("Must not request foreign album") }.use { fixture ->
            try {
                MemoriesAlbumRemover(fixture.writerTransport).remove(fixture.credentials, AlbumLocation.Remote("someone/album"), setOf(1))
                fail("Expected rejected foreign album")
            } catch (_: IllegalArgumentException) { }
            assertTrue(fixture.requests.isEmpty())
        }
    }

    @Test fun `DAV hrefs cannot escape album origin collection or file identity`() {
        MemoriesAlbumWriterTest.Fixture { 500 to "" }.use { fixture ->
            val root = albumUrl(fixture.origin, AlbumAddress("anna", "album"))
            val valid = listing(root.encodedPath)
            assertEquals(setOf(1L, 2L, 3L), albumMemberUrls(root, valid).keys)
            val href = root.encodedPath + "/1-a.jpg"
            listOf(
                "https://attacker.example/remote.php/dav/photos/anna/albums/album/1-a.jpg",
                "/remote.php/dav/files/anna/Photos/1-a.jpg",
                root.encodedPath + "/nested/1-a.jpg",
                root.encodedPath + "/2-a.jpg",
                root.encodedPath + "/1-a.jpg?x=1",
            ).forEach { invalid ->
                try {
                    albumMemberUrls(root, valid.replace(href, invalid))
                    fail("Accepted unsafe href $invalid")
                } catch (_: IllegalArgumentException) { }
            }
            try {
                albumMemberUrls(root, "<!DOCTYPE x [<!ENTITY external SYSTEM 'file:///etc/passwd'>]>" + valid)
                fail("Accepted external entities")
            } catch (_: IllegalArgumentException) { }
        }
    }

    @Test fun `capabilities distinguish folders owned albums foreign albums and malformed locations`() {
        assertTrue(supportsAlbumActions(AlbumLocation.Folder("external_primary", "Pictures/"), "anna"))
        assertTrue(supportsAlbumActions(AlbumLocation.Remote("anna/album"), "anna"))
        assertFalse(supportsAlbumActions(AlbumLocation.Remote("bob/album"), "anna"))
        assertFalse(supportsAlbumActions(AlbumLocation.Remote("anna"), "anna"))
        assertFalse(supportsAlbumActions(AlbumLocation.Remote("anna/album"), null))
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
