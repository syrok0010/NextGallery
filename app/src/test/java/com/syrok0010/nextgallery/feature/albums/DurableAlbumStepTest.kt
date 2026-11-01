package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.network.fileUrl
import com.syrok0010.nextgallery.feature.upload.UploadIntent
import com.syrok0010.nextgallery.feature.upload.AlbumStepMode
import com.syrok0010.nextgallery.feature.upload.UploadSelection
import com.syrok0010.nextgallery.feature.upload.UploadedFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Exercises the production action over real HTTP, including the XML and redirect guards. */
class DurableAlbumStepTest {
    private val target = "/remote.php/dav/photos/anna/albums/slice"
    private val source = "/remote.php/dav/files/anna/Photos/source.jpg"
    private val member = "$target/42-source.jpg"
    private val prefix = """xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns" xmlns:nc="http://nextcloud.org/ns""""
    private fun entry(href: String, props: String, status: String = "HTTP/1.1 200 OK") =
        """<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>$status</d:status></d:propstat></d:response>"""
    private val albumProps = """<d:resourcetype><d:collection/></d:resourcetype><nc:original-name>slice</nc:original-name><d:current-user-privilege-set><d:privilege><d:read/></d:privilege><d:privilege><d:bind/></d:privilege></d:current-user-privilege-set>"""
    private val sourceProps = """<d:resourcetype/><oc:fileid>42</oc:fileid><oc:owner-id>anna</oc:owner-id><oc:permissions>RG</oc:permissions><d:getetag>"etag"</d:getetag>"""
    private val memberProps = """<d:resourcetype/><oc:fileid>42</oc:fileid><nc:photos-collection-file-original-filename>/Photos/source.jpg</nc:photos-collection-file-original-filename>"""
    private fun xml(entries: String) = """<d:multistatus $prefix>$entries</d:multistatus>"""
    private fun albums(id: Int = 12) = """[{"album_id":$id,"name":"slice","user":"anna","cluster_id":"anna/slice"}]"""

    @Test fun `first upload adds the file once and verifies exact membership`() = runBlocking {
        var present = false
        MemoriesAlbumWriterTest.Fixture { request ->
            when {
                request.method == "COPY" -> { present = true; 201 to "" }
                request.target.contains("clusters/albums") -> 200 to albums()
                request.target == source -> 207 to xml(entry(source, sourceProps))
                request.target == target -> 207 to xml(entry(target, albumProps) + if (present) entry(member, memberProps) else "")
                else -> 404 to ""
            }
        }.use { f ->
            val step = DurableAlbumStep(f.writerTransport)
            val intent = UploadIntent("album", 2, "anna", "slice", 12, f.origin + target)
            val file = UploadedFile(UploadSelection("content://missing", "source.jpg", "image/jpeg"), fileUrl(f.origin, "anna", "/Photos/source.jpg"), true, 42)
            val result = step.run(f.credentials, intent, file, AlbumStepMode.Initial) { true }
            assertEquals("First addition must actually add the uploaded file", "Done", result.state)
            assertEquals(42L, result.fileId)
            assertEquals(12L, result.targetId)
            val copy = f.requests.single { it.method == "COPY" }
            assertEquals(f.origin + target + "/source.jpg", copy.headers["destination"])
            assertEquals("\"etag\"", copy.headers["if-match"])
            assertEquals("F", copy.headers["overwrite"])
            assertEquals("Done", step.run(f.credentials, intent, file) { true }.state)
            assertEquals(1, f.requests.count { it.method == "COPY" })
            assertTrue(f.requests.none { it.method == "PUT" })
        }
    }

    @Test fun `initial attempt never repeats a COPY whose outcome is not confirmed`() = runBlocking {
        for (status in listOf(201, 409, 412, 500)) {
            MemoriesAlbumWriterTest.Fixture { request ->
                when {
                    request.method == "COPY" -> status to ""
                    request.target.contains("clusters/albums") -> 200 to albums()
                    request.target == source -> 207 to xml(entry(source, sourceProps))
                    request.target == target -> 207 to xml(entry(target, albumProps))
                    else -> 404 to ""
                }
            }.use { f ->
                val step = DurableAlbumStep(f.writerTransport)
                val intent = UploadIntent("album", 2, "anna", "slice", 12, f.origin + target)
                val file = UploadedFile(UploadSelection("content://missing", "source.jpg", "image/jpeg"), fileUrl(f.origin, "anna", "/Photos/source.jpg"), true, 42)
                val result = step.run(f.credentials, intent, file, AlbumStepMode.Initial) { true }
                assertEquals(if (status in 400..499) "Attention" else "Uncertain", result.state)
                assertEquals("ReplayUnsupported", step.run(f.credentials, intent, file) { true }.problem)
                assertEquals(1, f.requests.count { it.method == "COPY" })
            }
        }
    }

    @Test fun `cancel after reconciliation prevents initial COPY`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { request ->
            when {
                request.target.contains("clusters/albums") -> 200 to albums()
                request.target == source -> 207 to xml(entry(source, sourceProps))
                request.target == target -> 207 to xml(entry(target, albumProps))
                else -> error("Cancelled attempt must not write")
            }
        }.use { f ->
            var checks = 0
            val intent = UploadIntent("album", 2, "anna", "slice", 12, f.origin + target)
            val file = UploadedFile(UploadSelection("content://missing", "source.jpg", null), fileUrl(f.origin, "anna", "/Photos/source.jpg"), true, 42)
            assertEquals("Stopped", DurableAlbumStep(f.writerTransport).run(f.credentials, intent, file, AlbumStepMode.Initial) { ++checks == 1 }.problem)
            assertTrue(f.requests.none { it.method == "COPY" })
        }
    }

    private suspend fun exercise(
        present: Boolean = true,
        albumResponse: String = xml(entry(target, albumProps) + if (present) entry(member, memberProps) else ""),
        sourceResponse: String = xml(entry(source, sourceProps)),
        id: Int = 12,
        expected: String = "Done",
        problem: String? = null,
        current: Boolean = true,
    ) {
        MemoriesAlbumWriterTest.Fixture { request ->
            when {
                request.method == "COPY" -> error("Read-only reconciliation must never COPY")
                request.target.contains("clusters/albums") -> 200 to albums(id)
                request.target == source -> 207 to sourceResponse
                request.target == target -> 207 to albumResponse
                else -> 404 to ""
            }
        }.use { f ->
            val step = DurableAlbumStep(f.writerTransport)
            val intent = UploadIntent("album", 2, "anna", "slice", 12, f.origin + target)
            val file = UploadedFile(UploadSelection("content://missing", "source.jpg", "image/jpeg"), fileUrl(f.origin, "anna", "/Photos/source.jpg"), true, 42)
            val result = step.run(f.credentials, intent, file) { current }
            assertEquals(expected, result.state)
            assertEquals(problem, result.problem)
            assertTrue(f.requests.none { it.method == "PUT" })
            assertTrue("Recovery must not COPY", f.requests.none { it.method == "COPY" })
            if (result.state == "Done") {
                assertEquals(42L, result.fileId); assertEquals(12L, result.targetId); assertEquals(f.origin + member, result.href)
            }
        }
    }

    @Test fun `server COPY before lost step commit reconciles exact membership without another write`() = runBlocking { exercise() }
    @Test fun `manual retry with absent member cannot replay name addressed COPY`() = runBlocking { exercise(present = false, expected = "Attention", problem = "ReplayUnsupported") }
    @Test fun `deleted and recreated album is never adopted`() = runBlocking { exercise(id = 13, expected = "Attention", problem = "TargetChanged") }
    @Test fun `missing album cannot be treated as existing`() = runBlocking { exercise(id = 0, expected = "Attention", problem = "TargetChanged") }
    @Test fun `replaced source file id suppresses addition`() = runBlocking { exercise(sourceResponse = xml(entry(source, sourceProps.replace(">42<", ">77<"))), expected = "Attention", problem = "SourceChanged") }
    @Test fun `source owner and failed propstat cannot prove identity`() = runBlocking {
        exercise(sourceResponse = xml(entry(source, sourceProps.replace(">anna<", ">other<"))), expected = "Attention", problem = "SourceChanged")
        exercise(sourceResponse = xml(entry(source, sourceProps, "HTTP/1.1 404 Not Found")), expected = "Attention", problem = "SourceChanged")
    }
    @Test fun `foreign source href and member file id are not accepted`() = runBlocking {
        exercise(sourceResponse = xml(entry("http://other.example$source", sourceProps)), expected = "Attention", problem = "Contract")
        exercise(albumResponse = xml(entry(target, albumProps) + entry(member, memberProps.replace(">42<", ">77<"))), expected = "Attention", problem = "Membership")
    }
    @Test fun `membership requires matching file id original path and exact href`() = runBlocking {
        exercise(albumResponse = xml(entry(target, albumProps) + entry(member, memberProps.replace("/Photos/source.jpg", "/Photos/other.jpg"))), expected = "Attention", problem = "SourceChanged")
        exercise(albumResponse = xml(entry(target, albumProps) + entry("$target/42-other.jpg", memberProps)), expected = "Attention", problem = "SourceChanged")
    }
    @Test fun `duplicate properties and missing membership fields fail closed`() = runBlocking {
        exercise(albumResponse = xml(entry(target, albumProps) + entry(member, memberProps + "<oc:fileid>42</oc:fileid>")), expected = "Attention", problem = "Contract")
        exercise(albumResponse = xml(entry(target, albumProps) + entry(member, "<d:resourcetype/>")), expected = "Attention", problem = "Membership")
    }
    @Test fun `existing empty album never authorizes a name addressed COPY`() = runBlocking { exercise(present = false, expected = "Attention", problem = "ReplayUnsupported") }
    @Test fun `cancel before beginning suppresses every call`() = runBlocking { exercise(current = false, expected = "Attention", problem = "Stopped") }
    @Test fun `collection privileges must prove bind and read`() = runBlocking { exercise(albumResponse = xml(entry(target, albumProps.replace("<d:bind/>", "")) + entry(member, memberProps)), expected = "Attention", problem = "Permission") }
    @Test fun `unknown intent and foreign origin do not make authenticated requests`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { 500 to "" }.use { f ->
            val step = DurableAlbumStep(f.writerTransport)
            val file = UploadedFile(UploadSelection("content://missing", "x", null), fileUrl("http://other.example", "anna", "x"), true, 42)
            assertEquals("LegacyTarget", step.run(f.credentials, UploadIntent("album", 99), file) { true }.problem)
            val intent = UploadIntent("album", 2, "anna", "slice", 12, f.origin + target)
            assertEquals("SourceChanged", step.run(f.credentials, intent, file) { true }.problem)
            assertTrue(f.requests.isEmpty())
        }
    }
    @Test fun `snapshot preserves selected collection identity rather than adopting replacement`() = runBlocking {
        MemoriesAlbumWriterTest.Fixture { 200 to albums(13) }.use { f ->
            val step = DurableAlbumStep(f.writerTransport)
            val old = UploadIntent("album", owner = "anna", target = "slice", albumId = 12)
            assertNull(step.snapshot(f.credentials, old).albumId)
            val fresh = step.snapshot(f.credentials, old.copy(albumId = null))
            assertEquals(2, fresh.version)
            assertEquals(13L, fresh.albumId)
            assertEquals(f.origin + target, fresh.albumHref)
        }
    }

    @Test fun `redirect response is never followed with authentication`() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        var redirected = 0
        server.createContext("/") { exchange ->
            if (exchange.requestURI.path == "/redirected") {
                redirected++
                exchange.sendResponseHeaders(200, -1)
            } else {
                exchange.responseHeaders.add("Location", "/redirected")
                exchange.sendResponseHeaders(302, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val origin = "http://127.0.0.1:${server.address.port}"
            val account = com.syrok0010.nextgallery.core.session.AccountCredentials(origin, "anna", "fixture", "anna")
            val transport = com.syrok0010.nextgallery.core.network.NextcloudTransport(kotlinx.serialization.json.Json)
            val step = DurableAlbumStep(transport)
            assertNull(step.snapshot(account, UploadIntent("album", owner = "anna", target = "slice")).albumId)
            assertEquals(0, redirected)
        } finally { server.stop(0) }
    }

}
