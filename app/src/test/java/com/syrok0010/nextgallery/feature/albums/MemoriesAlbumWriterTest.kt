package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudFixture
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.PhotosAlbumsDavClient
import java.net.URLDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

class MemoriesAlbumWriterTest {
    @Test fun `create sends MKCOL to encoded own album and maps 405 to existing album`() = runBlocking {
        var exists = false
        NextcloudFixture { if (exists) 405 to "" else 201 to "" }.use { fixture ->
            val location = fixture.writer.create(fixture.credentials, "Лето & море")
            assertEquals(AlbumLocation.Remote("anna/Лето & море"), location)
            val request = fixture.requests.single()
            assertEquals("MKCOL", request.method)
            assertEquals("/remote.php/dav/photos/anna/albums/Лето & море", request.decodedTarget)
            assertEquals(
                Credentials.basic("fixture", "password"),
                request.headers["authorization"],
            )
            exists = true
            try {
                fixture.writer.create(fixture.credentials, "Лето & море")
                fail("Expected existing album")
            } catch (_: AlbumAlreadyExistsException) {
            }
        }
    }

    @Test fun `add copies each server file into the album and classifies responses`() = runBlocking {
        NextcloudFixture { request ->
            when {
                request.method == "SEARCH" -> when (val id = request.searchedFileId) {
                    4L -> 207 to found()
                    else -> 207 to found(id to "Photos/Поход%20$id.jpg")
                }
                request.method == "COPY" && request.decodedTarget.endsWith("Поход 2.jpg") -> 409 to ""
                request.method == "COPY" && request.decodedTarget.endsWith("Поход 3.jpg") -> 403 to ""
                request.method == "COPY" -> 201 to ""
                request.method == "PROPFIND" -> 207 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val result = fixture.writer.add(
                album = AlbumAddress("anna", "Лето & море"),
                fileIds = listOf(1, 2, 3, 4, 1),
            )
            assertEquals(AlbumAddResult(added = 1, alreadyPresent = 1, failed = 2), result)
            val copy = fixture.requests.single { it.method == "COPY" && it.decodedTarget.endsWith("1.jpg") }
            assertEquals("/remote.php/dav/files/anna/Photos/Поход 1.jpg", copy.decodedTarget)
            assertEquals(
                "${fixture.origin}/remote.php/dav/photos/anna/albums/Лето & море/Поход 1.jpg",
                URLDecoder.decode(copy.headers.getValue("destination").replace("+", "%2B"), "UTF-8"),
            )
            assertFalse(copy.headers.getValue("destination").contains(' '))
            assertEquals(4, fixture.requests.count { it.method == "SEARCH" })
        }
    }

    @Test fun `add finds a file by id through DAV search, not Memories`() = runBlocking {
        NextcloudFixture { request ->
            when (request.method) {
                "SEARCH" -> 207 to found(7L to "Camera/Свежий.jpg")
                "COPY" -> 201 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            assertEquals(
                AlbumAddResult(added = 1, alreadyPresent = 0, failed = 0),
                fixture.writer.add(AlbumAddress("anna", "Лето"), listOf(7)),
            )
            val search = fixture.requests.first()
            assertEquals("/remote.php/dav/", search.target)
            assertEquals(7L, search.searchedFileId)
            assertTrue(search.body.contains("<d:href>/files/anna</d:href>"))
            assertEquals(
                "/remote.php/dav/files/anna/Camera/Свежий.jpg",
                fixture.requests.single { it.method == "COPY" }.decodedTarget,
            )
            assertFalse(fixture.requests.any { it.target.startsWith("/apps/memories") })
        }
    }

    @Test fun `cancelling a write cancels the HTTP call instead of waiting for the server`() = runBlocking {
        val release = CountDownLatch(1)
        NextcloudFixture {
            release.await(5, TimeUnit.SECONDS)
            201 to ""
        }.use { fixture ->
            try {
                val write = launch(Dispatchers.IO) {
                    fixture.writer.create(fixture.credentials, "Медленный")
                }
                withTimeout(5_000) { while (fixture.requests.isEmpty()) delay(10) }
                withTimeout(1_000) { write.cancelAndJoin() }
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `addFile copies an uploaded DAV path without a lookup`() = runBlocking {
        NextcloudFixture { 201 to "" }.use { fixture ->
            val source = DavFilePath.of("anna", "/Photos/Поход 7.jpg")
            assertEquals(
                AlbumCopyOutcome.Added,
                fixture.writer.addFile(source, AlbumAddress("anna", "Лето")),
            )
            val copy = fixture.requests.single()
            assertEquals("COPY", copy.method)
            assertEquals("/remote.php/dav/files/anna/Photos/Поход 7.jpg", copy.decodedTarget)
            assertEquals(
                "${fixture.origin}/remote.php/dav/photos/anna/albums/Лето/Поход 7.jpg",
                URLDecoder.decode(copy.headers.getValue("destination"), "UTF-8"),
            )
        }
    }

    @Test fun `conflict on a missing album is a failure, not already present`() = runBlocking {
        NextcloudFixture { request ->
            when {
                request.method == "SEARCH" -> 207 to found(1L to "Photos/a.jpg")
                request.method == "COPY" -> 409 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val album = AlbumAddress("anna", "Удалённый")
            assertEquals(
                AlbumCopyOutcome.Failed,
                fixture.writer.addFile(DavFilePath.of("anna", "/Photos/a.jpg"), album),
            )
            assertEquals(
                AlbumAddResult(added = 0, alreadyPresent = 0, failed = 1),
                fixture.writer.add(album, listOf(1)),
            )
            assertTrue(fixture.requests.any { it.method == "PROPFIND" && it.headers["depth"] == "0" })
        }
    }

    @Test fun `failed album check keeps added files and fails only unconfirmed conflicts`() = runBlocking {
        NextcloudFixture { request ->
            when {
                request.method == "SEARCH" ->
                    207 to found(request.searchedFileId to "Photos/${request.searchedFileId}.jpg")
                request.method == "COPY" && request.decodedTarget.endsWith("/2.jpg") -> 409 to ""
                request.method == "COPY" -> 201 to ""
                else -> 500 to ""
            }
        }.use { fixture ->
            assertEquals(
                AlbumAddResult(added = 1, alreadyPresent = 0, failed = 1),
                fixture.writer.add(AlbumAddress("anna", "Лето"), listOf(1, 2)),
            )
        }
    }

    @Test fun `album address splits cluster id into owner and collection`() {
        assertEquals(
            AlbumAddress("anna", "Лето & море"),
            AlbumAddress.of(AlbumLocation.Remote("anna/Лето & море")),
        )
        assertNull(AlbumAddress.of(AlbumLocation.Remote("anna")))
        assertNull(AlbumAddress.of(AlbumLocation.Remote("/name")))
    }
}

/** The album writer of the fixture's account. */
internal val NextcloudFixture.writer: MemoriesAlbumWriter
    get() = MemoriesAlbumWriter(PhotosAlbumsDavClient(transport) { credentials }, dav)

/** DAV SEARCH multistatus listing each file id at its encoded path under `files/anna`. */
internal fun found(vararg files: Pair<Long, String>): String =
    files.joinToString(
        "",
        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">""",
        "</d:multistatus>",
    ) { (id, path) ->
        "<d:response><d:href>/remote.php/dav/files/anna/$path</d:href><d:propstat>" +
            "<d:prop><oc:fileid>$id</oc:fileid></d:prop>" +
            "<d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    }
