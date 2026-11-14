package com.syrok0010.nextgallery.core.network

import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse

internal data class Recorded(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    val body: String,
) {
    val decodedTarget: String get() = URLDecoder.decode(target.replace("+", "%2B"), "UTF-8")

    /** The `oc:fileid` literal of a DAV SEARCH body. */
    val searchedFileId: Long
        get() = Regex("<(?:\\w+:)?literal>(\\d+)<").find(body)!!.groupValues[1].toLong()
}

/**
 * Nextcloud that answers with [respond]'s status and JSON body. A request is recorded before
 * [respond] runs, so a test sees a call that the server still holds.
 */
internal class NextcloudFixture(private val respond: (Recorded) -> Pair<Int, String>) :
    AutoCloseable {
    val requests = CopyOnWriteArrayList<Recorded>()
    private val server = loopbackServer { request ->
        val recorded = Recorded(
            request.method,
            request.target,
            request.headerMap,
            request.body?.utf8().orEmpty(),
        )
        requests += recorded
        val (code, body) = respond(recorded)
        MockResponse
            .Builder()
            .code(code)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()
    }
    val origin = server.origin

    /** The login name differs from the uid, as with an e-mail login; DAV paths use the uid. */
    val credentials = AccountCredentials(origin, "fixture", "password", userId = "anna")
    val transport = NextcloudTransport(Json { ignoreUnknownKeys = true })
    val dav = FilesDavClient(transport) { credentials }
    val memories = MemoriesApiClient(transport) { credentials }

    override fun close() {
        server.close()
    }
}
