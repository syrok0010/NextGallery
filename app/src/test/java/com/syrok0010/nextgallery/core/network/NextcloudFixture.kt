package com.syrok0010.nextgallery.core.network

import com.syrok0010.nextgallery.core.network.dav.FilesDavClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ServerSocketFactory
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/** A [MockWebServer] on loopback that answers every request with [respond]. */
internal fun loopbackServer(respond: (RecordedRequest) -> MockResponse): MockWebServer =
    MockWebServer().apply {
        serverSocketFactory = NoDelayServerSocketFactory
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = respond(request)
        }
        start(InetAddress.getLoopbackAddress(), 0)
    }

/**
 * MockWebServer writes a response's headers and body separately and keeps the connection, so with
 * Nagle's algorithm each response waits for the client's delayed ACK: about 40 ms a request.
 */
private object NoDelayServerSocketFactory : ServerSocketFactory() {
    override fun createServerSocket(): ServerSocket = NoDelayServerSocket()

    override fun createServerSocket(port: Int): ServerSocket =
        NoDelayServerSocket().apply { bind(InetSocketAddress(port)) }

    override fun createServerSocket(port: Int, backlog: Int): ServerSocket =
        NoDelayServerSocket().apply { bind(InetSocketAddress(port), backlog) }

    override fun createServerSocket(port: Int, backlog: Int, address: InetAddress): ServerSocket =
        NoDelayServerSocket().apply { bind(InetSocketAddress(address, port), backlog) }
}

private class NoDelayServerSocket : ServerSocket() {
    override fun accept(): Socket = super.accept().apply { tcpNoDelay = true }
}

/** The origin a test account points at: an address, so it never resolves to IPv6. */
internal val MockWebServer.origin: String get() = "http://127.0.0.1:$port"

/** Header names in lower case, as tests look them up. */
internal val RecordedRequest.headerMap: Map<String, String>
    get() = headers.associate { (name, value) -> name.lowercase() to value }

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
