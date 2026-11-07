package com.syrok0010.nextgallery.core.network.dav

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class FilesDavClientTest {
    private val file = DavFilePath.of("anna", "/Photos/Camera", "a/b.jpg")

    @Test fun `create puts without overwrite and reads the file id`() =
        runBlocking {
            DavFixtureServer {
                DavResponseStub(201, mapOf("OC-FileId" to "00000183ocmsigiy9k16"))
            }.use { fixture ->
                assertEquals(
                    CreateFileResult.Created(183),
                    fixture.client.createFile(file, "x".toRequestBody()),
                )
                val request = fixture.requests.single()
                assertEquals("PUT", request.method)
                assertEquals("*", request.headers["if-none-match"])
                // A slash in the name stays inside one segment.
                assertEquals("/remote.php/dav/files/anna/Photos/Camera/a%2Fb.jpg", request.target)
            }
        }

    @Test fun `create reports an existing file and fails on other statuses`() =
        runBlocking {
            var code = 412
            DavFixtureServer { DavResponseStub(code) }.use { fixture ->
                assertEquals(
                    CreateFileResult.AlreadyExists,
                    fixture.client.createFile(file, "x".toRequestBody()),
                )
                code = 507
                val error = runCatching {
                    fixture.client.createFile(
                        file,
                        "x".toRequestBody(),
                    )
                }.exceptionOrNull()
                assertEquals(507, (error as DavHttpException).code)
            }
        }

    @Test fun `properties read size, id and etag from the successful propstat`() =
        runBlocking {
            var code = 207
            DavFixtureServer { DavResponseStub(code, body = MULTISTATUS) }.use { fixture ->
                assertEquals(
                    DavFileProperties(size = 2048, fileId = 295, etag = "6543abcd"),
                    fixture.client.properties(file),
                )
                val request = fixture.requests.single()
                assertEquals("PROPFIND", request.method)
                assertEquals("0", request.headers["depth"])
                assertTrue(request.body.decodeToString().contains("oc:fileid"))
                code = 404
                assertNull(fixture.client.properties(file))
                code = 403
                val error = runCatching { fixture.client.properties(file) }.exceptionOrNull()
                assertEquals(403, (error as DavHttpException).code)
            }
        }

    @Test fun `properties ignore values outside a successful propstat`() {
        val properties = fileProperties(
            """
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response><d:propstat>
                <d:prop><oc:fileid>7</oc:fileid><d:getcontentlength>1</d:getcontentlength></d:prop>
                <d:status>HTTP/1.1 404 Not Found</d:status>
              </d:propstat></d:response>
            </d:multistatus>
            """.trimIndent().byteInputStream(),
        )
        assertEquals(DavFileProperties(size = null, fileId = null, etag = null), properties)
    }

    @Test fun `properties read a Nextcloud body with a declaration and a missing property`() {
        // Nextcloud 34 answer for a folder: getcontentlength comes back empty under 404.
        val properties = fileProperties(
            (
                "<?xml version=\"1.0\"?>\n<d:multistatus xmlns:d=\"DAV:\" " +
                    "xmlns:s=\"http://sabredav.org/ns\" xmlns:oc=\"http://owncloud.org/ns\" " +
                    "xmlns:nc=\"http://nextcloud.org/ns\"><d:response>" +
                    "<d:href>/remote.php/dav/files/test/Photos/</d:href><d:propstat><d:prop>" +
                    "<d:getetag>&quot;6aee17d638e4e&quot;</d:getetag><oc:fileid>87</oc:fileid>" +
                    "</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat><d:propstat>" +
                    "<d:prop><d:getcontentlength/></d:prop>" +
                    "<d:status>HTTP/1.1 404 Not Found</d:status></d:propstat></d:response>" +
                    "</d:multistatus>"
                ).byteInputStream(),
        )
        assertEquals(
            DavFileProperties(size = null, fileId = 87, etag = "6aee17d638e4e"),
            properties,
        )
    }

    @Test fun `properties refuse a document type declaration`() {
        val error = runCatching {
            fileProperties(
                "<!DOCTYPE d [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><d:multistatus xmlns:d=\"DAV:\"/>"
                    .byteInputStream(),
            )
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test fun `propfind body names the three properties in their namespaces`() {
        val body = encodeDav(PropFind())
        val document = javax.xml.parsers.DocumentBuilderFactory
            .newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(body.byteInputStream())
        val root = document.documentElement
        assertEquals("DAV:" to "propfind", root.namespaceURI to root.localName)
        val prop = root.getElementsByTagNameNS("DAV:", "prop").item(0)
        val names = (0 until prop.childNodes.length)
            .map { prop.childNodes.item(it) }
            .filter { it.nodeType == org.w3c.dom.Node.ELEMENT_NODE }
            .map { it.namespaceURI to it.localName }
        assertEquals(
            listOf(
                "DAV:" to "getcontentlength",
                "DAV:" to "getetag",
                "http://owncloud.org/ns" to "fileid",
            ),
            names,
        )
    }

    @Test fun `malformed properties fail as an IO error`() {
        val error = runCatching {
            fileProperties("<d:multistatus xmlns:d=\"DAV:\"><d:response>".byteInputStream())
        }.exceptionOrNull()
        assertTrue(error is IOException)
    }

    @Test fun `read is pinned to the etag and reports a replaced file`() =
        runBlocking {
            var code = 200
            DavFixtureServer { DavResponseStub(code, body = "bytes") }.use { fixture ->
                val read = fixture.client.readFile(
                    file,
                    "6543abcd",
                ) { it.readBytes().decodeToString() }
                assertEquals(ReadFileResult.Read("bytes"), read)
                val request = fixture.requests.single()
                assertEquals("GET", request.method)
                assertEquals("\"6543abcd\"", request.headers["if-match"])
                code = 412
                assertEquals(ReadFileResult.Changed, fixture.client.readFile(file, "6543abcd") { })
                code = 404
                assertEquals(ReadFileResult.Changed, fixture.client.readFile(file, null) { })
            }
        }

    @Test fun `file id is read from the OC-FileId header`() {
        assertEquals(183L, ocFileId("00000183ocmsigiy9k16"))
        assertEquals(123_456_789L, ocFileId("123456789ocmsigiy9k16"))
        assertNull(ocFileId(null))
        assertNull(ocFileId(""))
        assertNull(ocFileId("ocmsigiy9k16"))
        assertNull(ocFileId("00000000ocmsigiy9k16"))
    }

    private companion object {
        val MULTISTATUS =
            """
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
              <d:response>
                <d:href>/remote.php/dav/files/anna/Photos/Camera/a%2Fb.jpg</d:href>
                <d:propstat>
                  <d:prop>
                    <d:getcontentlength>2048</d:getcontentlength>
                    <d:getetag>&quot;6543abcd&quot;</d:getetag>
                    <oc:fileid>295</oc:fileid>
                  </d:prop>
                  <d:status>HTTP/1.1 200 OK</d:status>
                </d:propstat>
              </d:response>
            </d:multistatus>
            """.trimIndent()
    }
}
