package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import org.junit.Assert.*
import org.junit.Test

class MemoriesAlbumWriterTest {
    @Test fun `current user comes from OCS user id, not login name`() = runBlocking {
        Fixture { request ->
            if (request.target.startsWith("/ocs/v2.php/cloud/user")) {
                200 to """{"ocs":{"meta":{"status":"ok"},"data":{"id":"anna-uid","display-name":"Anna"}}}"""
            } else {
                404 to ""
            }
        }.use { fixture ->
            assertEquals("anna-uid", fixture.writer.currentUser(fixture.credentials))
        }
    }

    @Test fun `create sends MKCOL to encoded own album and maps 405 to existing album`() = runBlocking {
        var exists = false
        Fixture { if (exists) 405 to "" else 201 to "" }.use { fixture ->
            val location = fixture.writer.create(fixture.credentials, "anna", "Лето & море")
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
                fixture.writer.create(fixture.credentials, "anna", "Лето & море")
                fail("Expected existing album")
            } catch (_: AlbumAlreadyExistsException) {
            }
        }
    }

    @Test fun `add copies each server file into the album and classifies responses`() = runBlocking {
        Fixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") -> {
                    val id = request.target.substringAfterLast('/').toLong()
                    if (id == 4L) {
                        500 to ""
                    } else {
                        200 to """{"fileid":$id,"filename":"/Photos/Поход $id.jpg","basename":"Поход $id.jpg"}"""
                    }
                }
                request.method == "COPY" && request.decodedTarget.endsWith("Поход 2.jpg") -> 409 to ""
                request.method == "COPY" && request.decodedTarget.endsWith("Поход 3.jpg") -> 403 to ""
                request.method == "COPY" -> 201 to ""
                request.method == "PROPFIND" -> 207 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val result = fixture.writer.add(
                fixture.credentials,
                user = "anna",
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
        }
    }

    @Test fun `cancelling a write cancels the HTTP call instead of waiting for the server`() = runBlocking {
        val release = CountDownLatch(1)
        Fixture {
            release.await(5, TimeUnit.SECONDS)
            201 to ""
        }.use { fixture ->
            try {
                val write = launch(Dispatchers.IO) {
                    fixture.writer.create(fixture.credentials, "anna", "Медленный")
                }
                withTimeout(5_000) { while (fixture.requests.isEmpty()) delay(10) }
                withTimeout(1_000) { write.cancelAndJoin() }
            } finally {
                release.countDown()
            }
        }
    }

    @Test fun `addFile copies an uploaded DAV path without Memories lookup`() = runBlocking {
        Fixture { 201 to "" }.use { fixture ->
            val source = fileUrl(fixture.origin, "anna", "/Photos/Поход 7.jpg")
            assertEquals(
                AlbumCopyOutcome.Added,
                fixture.writer.addFile(fixture.credentials, source, AlbumAddress("anna", "Лето")),
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
        Fixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") ->
                    200 to """{"filename":"/Photos/a.jpg","basename":"a.jpg"}"""
                request.method == "COPY" -> 409 to ""
                else -> 404 to ""
            }
        }.use { fixture ->
            val album = AlbumAddress("anna", "Удалённый")
            assertEquals(
                AlbumCopyOutcome.Failed,
                fixture.writer.addFile(fixture.credentials, fileUrl(fixture.origin, "anna", "/Photos/a.jpg"), album),
            )
            assertEquals(
                AlbumAddResult(added = 0, alreadyPresent = 0, failed = 1),
                fixture.writer.add(fixture.credentials, "anna", album, listOf(1)),
            )
            assertTrue(fixture.requests.any { it.method == "PROPFIND" && it.headers["depth"] == "0" })
        }
    }

    @Test fun `failed album check keeps added files and fails only unconfirmed conflicts`() = runBlocking {
        Fixture { request ->
            when {
                request.target.startsWith("/apps/memories/api/image/info/") -> {
                    val id = request.target.substringAfterLast('/')
                    200 to """{"filename":"/Photos/$id.jpg","basename":"$id.jpg"}"""
                }
                request.method == "COPY" && request.decodedTarget.endsWith("/2.jpg") -> 409 to ""
                request.method == "COPY" -> 201 to ""
                else -> 500 to ""
            }
        }.use { fixture ->
            assertEquals(
                AlbumAddResult(added = 1, alreadyPresent = 0, failed = 1),
                fixture.writer.add(fixture.credentials, "anna", AlbumAddress("anna", "Лето"), listOf(1, 2)),
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

    @Test fun `copy outcome treats conflict as already present`() {
        assertEquals(AlbumCopyOutcome.Added, albumCopyOutcome(201))
        assertEquals(AlbumCopyOutcome.Added, albumCopyOutcome(204))
        assertEquals(AlbumCopyOutcome.AlreadyPresent, albumCopyOutcome(409))
        assertEquals(AlbumCopyOutcome.Failed, albumCopyOutcome(412))
        assertEquals(AlbumCopyOutcome.Failed, albumCopyOutcome(500))
    }

    internal data class Recorded(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    ) {
        val decodedTarget: String get() = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8")
    }

    internal class Fixture(
        private val respond: (Recorded) -> Pair<Int, String>,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
        private val executor = Executors.newSingleThreadExecutor()
        val requests = CopyOnWriteArrayList<Recorded>()
        val origin = "http://127.0.0.1:${server.localPort}"
        val credentials = AccountCredentials(origin, "fixture", "password")
        val writerTransport = NextcloudTransport(Json { ignoreUnknownKeys = true })
        val writer = MemoriesAlbumWriter(writerTransport)

        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val lines = generateSequence {
                            reader.readLine()?.takeIf(String::isNotEmpty)
                        }.toList()
                        val (method, target) = lines.first().split(' ')
                        val request = Recorded(
                            method,
                            target,
                            lines.drop(1).associate { line ->
                                line.substringBefore(':').lowercase() to line.substringAfter(':').trim()
                            },
                        )
                        requests += request
                        val (code, body) = respond(request)
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write(
                                (
                                    "HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                            )
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }
}
