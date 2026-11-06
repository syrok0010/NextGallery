package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavFixtureServer
import com.syrok0010.nextgallery.core.network.dav.DavRequest
import com.syrok0010.nextgallery.core.network.dav.DavResponseStub
import com.syrok0010.nextgallery.core.network.dav.url
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class UploadPlacementTest {
    private val phone = "phone bytes".toByteArray()
    private val path = DavFilePath.of("anna", "/Photos", "a.jpg")

    @Test fun `free name is checked and created once`() =
        runBlocking {
            Cloud().use { cloud ->
                val uploaded = cloud.place()

                assertEquals(
                    UploadedFile(SELECTION, cloud.url(path), created = true, fileId = 1),
                    uploaded,
                )
                assertEquals(listOf("PROPFIND a.jpg", "PUT a.jpg"), cloud.calls())
                assertArrayEquals(phone, cloud.files.getValue("a.jpg").bytes)
            }
        }

    @Test fun `identical file under the name is linked without sending it again`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile(phone, id = 295)

                val uploaded = cloud.place()

                assertEquals(
                    UploadedFile(SELECTION, cloud.url(path), created = false, fileId = 295),
                    uploaded,
                )
                assertEquals(listOf("PROPFIND a.jpg", "GET a.jpg"), cloud.calls())
            }
        }

    @Test fun `different size moves to the next name without downloading`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile("another photo".toByteArray(), id = 7)

                val uploaded = cloud.place()

                assertEquals(cloud.neighbour("a (2).jpg"), uploaded?.url)
                assertTrue(uploaded!!.created)
                assertEquals(
                    listOf("PROPFIND a.jpg", "PROPFIND a (2).jpg", "PUT a (2).jpg"),
                    cloud.calls(),
                )
                assertEquals(7L, cloud.files.getValue("a.jpg").id)
            }
        }

    @Test fun `same size with other bytes is a different file`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile("phone bytez".toByteArray(), id = 7)

                val uploaded = cloud.place()

                assertEquals(cloud.neighbour("a (2).jpg"), uploaded?.url)
                assertEquals(
                    listOf("PROPFIND a.jpg", "GET a.jpg", "PROPFIND a (2).jpg", "PUT a (2).jpg"),
                    cloud.calls(),
                )
            }
        }

    @Test fun `an earlier copy under a neighbour name is found again`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile("another photo".toByteArray(), id = 7)
                cloud.files["a (2).jpg"] = StoredFile(phone, id = 8)

                val uploaded = cloud.place()

                assertEquals(
                    UploadedFile(
                        SELECTION,
                        cloud.neighbour("a (2).jpg"),
                        created = false,
                        fileId = 8,
                    ),
                    uploaded,
                )
                assertFalse(cloud.calls().any { it.startsWith("PUT") })
            }
        }

    @Test fun `file replaced before the download is checked again`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile("phone bytez".toByteArray(), id = 7)
                // The property set describes the old version; the download then sees a new ETag.
                cloud.beforeGet = { name ->
                    if (name == "a.jpg" && cloud.files.getValue(name).id == 7L) {
                        cloud.files[name] = StoredFile(phone, id = 9)
                    }
                }

                val uploaded = cloud.place()

                assertEquals(
                    UploadedFile(SELECTION, cloud.url(path), created = false, fileId = 9),
                    uploaded,
                )
                assertEquals(
                    listOf("PROPFIND a.jpg", "GET a.jpg", "PROPFIND a.jpg", "GET a.jpg"),
                    cloud.calls(),
                )
            }
        }

    @Test fun `name taken between the check and PUT is compared`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.beforePut = { name ->
                    if (name == "a.jpg") cloud.files[name] = StoredFile(phone, id = 11)
                }

                val uploaded = cloud.place()

                assertEquals(
                    UploadedFile(SELECTION, cloud.url(path), created = false, fileId = 11),
                    uploaded,
                )
                assertEquals(
                    listOf("PROPFIND a.jpg", "PUT a.jpg", "PROPFIND a.jpg", "GET a.jpg"),
                    cloud.calls(),
                )
            }
        }

    @Test fun `no free name leaves the file out`() =
        runBlocking {
            Cloud().use { cloud ->
                cloud.files["a.jpg"] = StoredFile("x".toByteArray(), id = 1)
                (2..100).forEach {
                    cloud.files["a ($it).jpg"] = StoredFile(
                        "x".toByteArray(),
                        id = it.toLong(),
                    )
                }

                assertNull(cloud.place())
                assertFalse(cloud.calls().any { it.startsWith("PUT") })
            }
        }

    @Test fun `neighbour names keep the extension`() {
        assertEquals("IMG_1 (2).jpg", neighbourName("IMG_1.jpg", 2))
        assertEquals("archive.tar (3).gz", neighbourName("archive.tar.gz", 3))
        assertEquals(".nomedia (2)", neighbourName(".nomedia", 2))
        assertEquals("README (2)", neighbourName("README", 2))
    }

    @Test fun `bytes compare to the end of both streams`() =
        runBlocking {
            val large = ByteArray(200_000) { it.toByte() }
            assertTrue(sameBytes(large.inputStream(), large.copyOf().inputStream()))
            assertFalse(sameBytes(large.inputStream(), large.copyOf(199_999).inputStream()))
            assertFalse(
                sameBytes(
                    large.inputStream(),
                    large.copyOf().also { it[150_000] = 0 }.inputStream(),
                ),
            )
            assertTrue(sameBytes(ByteArray(0).inputStream(), ByteArray(0).inputStream()))
        }

    private fun Cloud.neighbour(name: String) = url(DavFilePath.of("anna", "/Photos", name))

    private fun Cloud.url(file: DavFilePath) = file.url(server.credentials.serverUrl)

    private suspend fun Cloud.place(): UploadedFile? =
        placeUpload(
            dav = server.client,
            serverUrl = server.credentials.serverUrl,
            path = path,
            selection = SELECTION,
            body = phone.toRequestBody(),
            openLocal = { phone.inputStream() },
        )

    private class StoredFile(val bytes: ByteArray, val id: Long) {
        val etag = "etag-$id-${bytes.contentHashCode()}"
    }

    /** DAV `Photos` folder of `anna`: PROPFIND, PUT with `If-None-Match`, GET with `If-Match`. */
    private class Cloud : AutoCloseable {
        val files = ConcurrentHashMap<String, StoredFile>()
        private val nextId = AtomicLong(1)
        var beforeGet: (String) -> Unit = {}
        var beforePut: (String) -> Unit = {}
        val server = DavFixtureServer(::respond)

        fun calls(): List<String> = server.requests.map { "${it.method} ${it.name()}" }

        private fun respond(request: DavRequest): DavResponseStub {
            val name = request.name()
            return when (request.method) {
                "PROPFIND" -> files[name]?.let { file ->
                    DavResponseStub(207, body = multistatus(file))
                } ?: DavResponseStub(404)

                "PUT" -> {
                    beforePut(name)
                    if (files.containsKey(name)) {
                        DavResponseStub(412)
                    } else {
                        val id = nextId.getAndIncrement()
                        files[name] = StoredFile(request.body, id)
                        DavResponseStub(201, mapOf("OC-FileId" to "%08docinstance".format(id)))
                    }
                }

                "GET" -> {
                    beforeGet(name)
                    val file = files[name]
                    when {
                        file == null -> DavResponseStub(404)
                        request.headers["if-match"] != "\"${file.etag}\"" -> DavResponseStub(412)
                        else -> DavResponseStub(200, body = file.bytes)
                    }
                }

                else -> DavResponseStub(405)
            }
        }

        private fun multistatus(file: StoredFile) =
            """
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:propstat><d:prop>
                <d:getcontentlength>${file.bytes.size}</d:getcontentlength>
                <d:getetag>&quot;${file.etag}&quot;</d:getetag>
                <oc:fileid>${file.id}</oc:fileid>
              </d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>
            </d:multistatus>
            """.trimIndent()

        override fun close() = server.close()

        private fun DavRequest.name() =
            URLDecoder.decode(target.substringAfterLast('/'), Charsets.UTF_8.name())
    }

    private companion object {
        val SELECTION = UploadSelection("content://media/1", "a.jpg", "image/jpeg")
    }
}
