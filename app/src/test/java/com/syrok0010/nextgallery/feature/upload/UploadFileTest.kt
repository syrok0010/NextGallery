package com.syrok0010.nextgallery.feature.upload

import com.sun.net.httpserver.HttpServer
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.media.mediaIdentityCandidate
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.feature.timeline.remote.InMemoryMediaIdentityRegistry
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class UploadFileTest {
    @Test
    fun `412 with equal size links existing copy even when aliases differ`() = runBlocking {
        Fixture().use { fixture ->
            val registry = InMemoryMediaIdentityRegistry()
            val local = MediaSourceIdentity(MediaSourceKind.Local, SELECTION.contentUri)
            val remote = MediaSourceIdentity(MediaSourceKind.Memories, "183")
            val localCandidate = mediaIdentityCandidate(local, auid = "phone-date-and-size", buid = null)
            val remoteCandidate = mediaIdentityCandidate(remote, auid = "different-cloud-date-and-size", buid = null)
            val before = registry.resolve(listOf(localCandidate, remoteCandidate)).mediaIds
            assertNotEquals(before[local], before[remote])
            val followUp = UploadedCopiesFollowUp(registry, fixture.sessions)

            val uploaded = fixture.upload()
            assertNotNull(uploaded)
            assertFalse(uploaded!!.created)
            assertEquals(183L, uploaded.fileId)
            uploaded.fileId?.let { followUp.link(fixture.credentials, uploaded.selection.identityUri, it) }

            val after = registry.resolve(listOf(localCandidate, remoteCandidate)).mediaIds
            assertEquals(before[local], after[local])
            assertEquals(after[local], after[remote])
            assertEquals(listOf("PUT", "HEAD", "PROPFIND"), fixture.methods)
        }
    }

    @Test
    fun `412 with different size does not reach follow-ups or request file id`() = runBlocking {
        Fixture(remoteSize = 99).use { fixture ->
            val registry = InMemoryMediaIdentityRegistry()
            val local = MediaSourceIdentity(MediaSourceKind.Local, SELECTION.contentUri)
            val remote = MediaSourceIdentity(MediaSourceKind.Memories, "183")
            val candidates = listOf(mediaIdentityCandidate(local, auid = null, buid = null), mediaIdentityCandidate(remote, auid = null, buid = null))
            val before = registry.resolve(candidates).mediaIds
            val followUp = UploadedCopiesFollowUp(registry, fixture.sessions)

            val uploaded = fixture.upload()
            uploaded?.fileId?.let { followUp.link(fixture.credentials, uploaded.selection.identityUri, it) }

            assertNull(uploaded)
            assertEquals(before, registry.resolve(candidates).mediaIds)
            assertNotEquals(before[local], before[remote])
            assertEquals(listOf("PUT", "HEAD"), fixture.methods)
        }
    }

    @Test
    fun `replacement with a different size between HEAD and PROPFIND is not linked`() = runBlocking {
        Fixture(xml = properties("<d:getcontentlength>99</d:getcontentlength><oc:fileid>999</oc:fileid>")).use { fixture ->
            val registry = InMemoryMediaIdentityRegistry()
            val local = MediaSourceIdentity(MediaSourceKind.Local, SELECTION.contentUri)
            val replacement = MediaSourceIdentity(MediaSourceKind.Memories, "999")
            val candidates = listOf(
                mediaIdentityCandidate(local, auid = null, buid = null),
                mediaIdentityCandidate(replacement, auid = null, buid = null),
            )
            val before = registry.resolve(candidates).mediaIds
            val followUp = UploadedCopiesFollowUp(registry, fixture.sessions)

            // HEAD still reports the original five bytes, but PROPFIND sees its replacement.
            val uploaded = fixture.upload()
            assertNotNull(uploaded)
            assertFalse(uploaded!!.created)
            assertNull(uploaded.fileId)
            uploaded.fileId?.let { followUp.link(fixture.credentials, uploaded.selection.identityUri, it) }

            assertEquals(before, registry.resolve(candidates).mediaIds)
            assertNotEquals(before[local], before[replacement])
            assertEquals(listOf("PUT", "HEAD", "PROPFIND"), fixture.methods)
        }
    }

    @Test
    fun `fileid without a valid successful DAV size is not linked`() {
        listOf(
            properties("<oc:fileid>183</oc:fileid>"),
            properties("<d:getcontentlength>invalid</d:getcontentlength><oc:fileid>183</oc:fileid>"),
            properties("<x:getcontentlength xmlns:x=\"urn:other\">5</x:getcontentlength><oc:fileid>183</oc:fileid>"),
            properties("<oc:fileid>183</oc:fileid>")
                .replace("</d:response>", "<d:propstat><d:prop><d:getcontentlength>5</d:getcontentlength></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>"),
        ).forEach { xml ->
            Fixture(xml = xml).use { fixture ->
                val uploaded = fixture.upload()
                assertNotNull(uploaded)
                assertFalse(uploaded!!.created)
                assertNull(uploaded.fileId)
            }
        }
    }

    @Test
    fun `failed or missing fileid keeps confirmed upload successful without a link`() {
        listOf(
            500 to "",
            207 to properties("<d:getcontentlength>5</d:getcontentlength><oc:fileid>183</oc:fileid>", "404 Not Found"),
            207 to properties("<d:getcontentlength>5</d:getcontentlength><oc:fileid>0</oc:fileid>"),
            207 to properties("<d:getcontentlength>5</d:getcontentlength><x:fileid xmlns:x=\"urn:other\">183</x:fileid>"),
            207 to properties("<d:getcontentlength>5</d:getcontentlength>"),
            207 to "not xml",
        ).forEach { (status, xml) ->
            Fixture(propfindStatus = status, xml = xml).use { fixture ->
                val uploaded = fixture.upload()
                assertNotNull(uploaded)
                assertFalse(uploaded!!.created)
                assertNull(uploaded.fileId)
            }
        }
    }

    @Test
    fun `successful PUT uses its header without probing existing file`() {
        listOf(201, 204).forEach { code ->
            Fixture(putStatus = code).use { fixture ->
                val uploaded = fixture.upload()
                assertTrue(uploaded!!.created)
                assertEquals(190L, uploaded.fileId)
                assertEquals(listOf("PUT"), fixture.methods)
            }
        }
    }

    @Test
    fun `missing destination reports actionable directory error and stops without follow-up probes`() {
        listOf(404, 409).forEach { status ->
            Fixture(putStatus = status).use { fixture ->
                val error = assertThrows(UploadException::class.java) { fixture.upload() }
                assertEquals(R.string.upload_error_directory, error.reason)
                assertEquals(listOf(status, "a.jpg"), error.args.toList())
                assertEquals(listOf("PUT"), fixture.methods)
            }
        }
    }

    @Test
    fun `unknown local size or failed HEAD cannot confirm an existing file`() {
        Fixture(headStatus = 404).use { assertNull(it.upload()) }
        Fixture().use {
            assertNull(it.upload(unknownLength = true))
            assertEquals(listOf("PUT"), it.methods)
        }
    }

    private class Fixture(
        private val remoteSize: Int = BYTES.size,
        private val putStatus: Int = 412,
        private val headStatus: Int = 200,
        private val propfindStatus: Int = 207,
        private val xml: String = properties("<d:getcontentlength>5</d:getcontentlength><oc:fileid>183</oc:fileid>"),
    ) : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val credentials = AccountCredentials("http://127.0.0.1:${server.address.port}", "test", "password", "uid")
        val sessions = SessionStore(object : CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
            override fun clear() = Unit
        })
        val methods = CopyOnWriteArrayList<String>()
        private val transport = NextcloudTransport(Json)
        private val url = "${credentials.serverUrl}/remote.php/dav/files/uid/Photos/a.jpg".toHttpUrl()

        init {
            server.createContext("/") { exchange ->
                exchange.use {
                    assertEquals(url.encodedPath, it.requestURI.path)
                    assertEquals(Credentials.basic("test", "password"), it.requestHeaders.getFirst("Authorization"))
                    val requestBody = it.requestBody.readBytes().toString(Charsets.UTF_8)
                    methods += it.requestMethod
                    when (it.requestMethod) {
                        "PUT" -> {
                            assertEquals("*", it.requestHeaders.getFirst("If-None-Match"))
                            // A 412 header must not be trusted, even if a server sends one.
                            it.responseHeaders.set("OC-FileId", "00000190ocfixture")
                            it.sendResponseHeaders(putStatus, -1)
                        }
                        "HEAD" -> {
                            it.responseHeaders.set("Content-Length", remoteSize.toString())
                            it.sendResponseHeaders(headStatus, -1)
                        }
                        "PROPFIND" -> {
                            assertEquals("0", it.requestHeaders.getFirst("Depth"))
                            assertTrue(requestBody.contains("<oc:fileid/>"))
                            assertTrue(requestBody.contains("<d:getcontentlength/>"))
                            val bytes = xml.toByteArray()
                            it.sendResponseHeaders(propfindStatus, bytes.size.toLong())
                            it.responseBody.write(bytes)
                        }
                        else -> error("Unexpected ${it.requestMethod}")
                    }
                }
            }
            server.start()
        }

        fun upload(unknownLength: Boolean = false): UploadedFile? {
            val body = if (unknownLength) {
                object : okhttp3.RequestBody() {
                    override fun contentType() = null
                    override fun writeTo(sink: okio.BufferedSink) { sink.write(BYTES) }
                }
            } else {
                BYTES.toRequestBody()
            }
            return uploadFile(transport, credentials, url, SELECTION, body)
        }

        override fun close() { server.stop(0) }
    }

    companion object {
        private val BYTES = "photo".toByteArray()
        private val SELECTION = UploadSelection("content://media/external/images/media/42", "a.jpg", "image/jpeg")

        private fun properties(content: String, status: String = "200 OK") =
            """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:propstat><d:prop>$content</d:prop><d:status>HTTP/1.1 $status</d:status></d:propstat></d:response></d:multistatus>"""
    }
}
