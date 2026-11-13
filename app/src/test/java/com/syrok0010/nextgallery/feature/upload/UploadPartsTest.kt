package com.syrok0010.nextgallery.feature.upload

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.dav.CreateFileResult
import com.syrok0010.nextgallery.core.network.dav.DavFilePath
import com.syrok0010.nextgallery.core.network.dav.DavFixtureServer
import com.syrok0010.nextgallery.core.network.dav.DavRequest
import com.syrok0010.nextgallery.core.network.dav.DavResponseStub
import com.syrok0010.nextgallery.core.network.dav.UploadsDavClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class UploadPartsTest {
    private val phone = "0123456789".toByteArray()
    private val target = DavFilePath.of("anna", "/Photos", "a.jpg")

    @Test fun `a new upload sends every part and assembles them without overwriting`() =
        runBlocking {
            Uploads().use { uploads ->
                assertEquals(CreateFileResult.Created(5), uploads.create())

                assertEquals(listOf("PROPFIND", "MKCOL", "PUT 1", "PUT 2", "PUT 3", "MOVE .file"), uploads.calls())
                assertArrayEquals(phone, uploads.created)
                val move = uploads.server.requests.last()
                assertEquals("F", move.headers["overwrite"])
                assertEquals("10", move.headers["oc-total-length"])
                assertTrue(move.headers.getValue("destination").endsWith("/remote.php/dav/files/anna/Photos/a.jpg"))
                val writes = uploads.server.requests.filter { it.method in setOf("MKCOL", "PUT", "MOVE") }
                assertTrue(writes.all { it.headers["destination"] == move.headers["destination"] })
            }
        }

    @Test fun `an interrupted upload sends only the missing parts`() =
        runBlocking {
            Uploads().use { uploads ->
                uploads.parts = mutableMapOf(1 to "0123".toByteArray(), 2 to "4567".toByteArray())

                uploads.create()

                assertEquals(listOf("PROPFIND", "PUT 3", "MOVE .file"), uploads.calls())
                assertArrayEquals(phone, uploads.created)
            }
        }

    @Test fun `a resumed upload with every part stored reports them sent before the server joins them`() =
        runBlocking {
            Uploads().use { uploads ->
                uploads.parts = mutableMapOf(
                    1 to "0123".toByteArray(),
                    2 to "4567".toByteArray(),
                    3 to "89".toByteArray(),
                )
                var callsWhenSent: List<String>? = null

                createInParts(uploads = uploads.client, id = "job", target = target, length = phone.size.toLong(), partSize = 4L, part = { _, _ -> error("no part is sent again") }) {
                    callsWhenSent = uploads.calls()
                }

                assertEquals(listOf("PROPFIND"), callsWhenSent)
                assertEquals(listOf("PROPFIND", "MOVE .file"), uploads.calls())
            }
        }

    @Test fun `parts of another size are dropped and the upload starts over`() =
        runBlocking {
            Uploads().use { uploads ->
                uploads.parts = mutableMapOf(1 to "01234".toByteArray())

                uploads.create()

                assertEquals(
                    listOf("PROPFIND", "DELETE", "MKCOL", "PUT 1", "PUT 2", "PUT 3", "MOVE .file"),
                    uploads.calls(),
                )
                assertArrayEquals(phone, uploads.created)
            }
        }

    @Test fun `an upload whose state the server lost is dropped and the next attempt starts over`() =
        runBlocking {
            Uploads().use { uploads ->
                // Object storage: the folder stays, but parts are refused with 412.
                uploads.beforePut = { number -> if (number == 2) uploads.lost = true }

                assertThrows(IOException::class.java) { runBlocking { uploads.create() } }
                assertEquals(listOf("PROPFIND", "MKCOL", "PUT 1", "PUT 2", "DELETE"), uploads.calls())

                uploads.beforePut = {}
                uploads.server.requests.clear()
                uploads.create()

                assertEquals(
                    listOf("PROPFIND", "MKCOL", "PUT 1", "PUT 2", "PUT 3", "MOVE .file"),
                    uploads.calls(),
                )
                assertArrayEquals(phone, uploads.created)
            }
        }

    @Test fun `a taken name drops the parts it was sent for`() =
        runBlocking {
            Uploads().use { uploads ->
                uploads.taken = true

                assertEquals(CreateFileResult.AlreadyExists, uploads.create())

                assertEquals("DELETE", uploads.calls().last())
                assertNull(uploads.parts)
            }
        }

    private suspend fun Uploads.create(): CreateFileResult =
        createInParts(client, "job", target, phone.size.toLong(), partSize = 4L, part = { offset, size ->
            phone.copyOfRange(offset.toInt(), (offset + size).toInt()).toRequestBody()
        })

    /** `uploads/anna/job` of a server that assembles the parts in order on `MOVE .file`. */
    private class Uploads : AutoCloseable {
        /** Stored parts; null while the upload does not exist. */
        @Volatile var parts: MutableMap<Int, ByteArray>? = null
        var created: ByteArray? = null
        var taken = false
        var lost = false
        var beforePut: (Int) -> Unit = {}
        val server = DavFixtureServer(::respond)
        val client = UploadsDavClient(NextcloudTransport(Json)) { server.credentials }
        private val lock = Any()

        fun calls(): List<String> =
            server.requests.map { request ->
                val child = request.target.substringAfter("/uploads/anna/job").trim('/')
                if (child.isEmpty()) request.method else "${request.method} $child"
            }

        private fun respond(request: DavRequest): DavResponseStub = synchronized(lock) {
            val child = request.target.substringAfter("/uploads/anna/job").trim('/')
            when (request.method) {
                "PROPFIND" -> {
                    parts?.let { DavResponseStub(207, body = listing(it)) } ?: DavResponseStub(404)
                }

                "MKCOL" -> {
                    if (parts == null) parts = ConcurrentHashMap()
                    DavResponseStub(201)
                }

                "PUT" -> {
                    val number = child.toInt()
                    beforePut(number)
                    when {
                        lost -> DavResponseStub(412)
                        else -> parts?.let {
                            it[number] = request.body
                            DavResponseStub(201)
                        } ?: DavResponseStub(404)
                    }
                }

                "MOVE" -> {
                    val stored = parts ?: return@synchronized DavResponseStub(404)
                    if (taken) return@synchronized DavResponseStub(412)
                    created = stored.toSortedMap().values.fold(ByteArray(0)) { all, part -> all + part }
                    parts = null
                    DavResponseStub(201, mapOf("OC-FileId" to "00000005ocinstance"))
                }

                "DELETE" -> {
                    parts = null
                    lost = false
                    DavResponseStub(204)
                }

                else -> DavResponseStub(405)
            }
        }

        private fun listing(parts: Map<Int, ByteArray>) =
            """
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/uploads/anna/job/</d:href>
                <d:propstat><d:prop><d:getcontentlength/></d:prop><d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
              </d:response>
              <d:response><d:href>/remote.php/dav/uploads/anna/job/.file</d:href>
                <d:propstat><d:prop><d:getcontentlength>${parts.values.sumOf { it.size }}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
              </d:response>
            """.trimIndent() +
                parts.entries.joinToString("") { (number, bytes) ->
                    """
                    <d:response><d:href>/remote.php/dav/uploads/anna/job/$number</d:href>
                      <d:propstat><d:prop><d:getcontentlength>${bytes.size}</d:getcontentlength></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
                    </d:response>
                    """.trimIndent()
                } + "</d:multistatus>"

        override fun close() = server.close()
    }
}
