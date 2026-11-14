package com.syrok0010.nextgallery.core.network.dav

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.headerMap
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.origin
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import okio.Buffer
internal class DavRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: ByteArray,
)

internal class DavResponseStub(
    val code: Int,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    constructor(code: Int, headers: Map<String, String> = emptyMap(), body: String) :
        this(code, headers, body.toByteArray())
}

/** HTTP server for DAV client tests; user is `anna`. */
internal class DavFixtureServer(private val respond: (DavRequest) -> DavResponseStub) :
    AutoCloseable {
    val requests = CopyOnWriteArrayList<DavRequest>()
    private val server = loopbackServer { request ->
        val recorded = DavRequest(
            request.method,
            request.target,
            request.headerMap,
            request.body?.toByteArray() ?: ByteArray(0),
        )
        requests += recorded
        val response = respond(recorded)
        MockResponse
            .Builder()
            .code(response.code)
            .apply { response.headers.forEach { (name, value) -> setHeader(name, value) } }
            .body(Buffer().write(response.body))
            .build()
    }
    val credentials = AccountCredentials(server.origin, "fixture", "password", userId = "anna")
    val client = FilesDavClient(NextcloudTransport(Json)) { credentials }

    override fun close() {
        server.close()
    }
}
