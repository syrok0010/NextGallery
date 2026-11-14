package com.syrok0010.nextgallery.core.network

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ServerSocketFactory
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
        // Not getLoopbackAddress(): on Android that is `::1`, and accounts point at 127.0.0.1.
        start(InetAddress.getByName(LOOPBACK), 0)
    }

private const val LOOPBACK = "127.0.0.1"

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

/** The origin a test account points at. */
internal val MockWebServer.origin: String get() = "http://$LOOPBACK:$port"

/** Header names in lower case, as tests look them up. */
internal val RecordedRequest.headerMap: Map<String, String>
    get() = headers.associate { (name, value) -> name.lowercase() to value }
