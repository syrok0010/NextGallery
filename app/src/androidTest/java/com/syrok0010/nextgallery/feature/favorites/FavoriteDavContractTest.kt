package com.syrok0010.nextgallery.feature.favorites

import androidx.test.ext.junit.runners.AndroidJUnit4
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FavoriteDavContractTest {
    @Test
    fun emptyFavoritePropertyInSuccessfulPropstatConfirmsOnAndroid() {
        // Real SabreDAV PROPPATCH response confirms the property without returning its value.
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns">
            <d:response><d:href>/remote.php/dav/files/test/Photos/ng129-favorites/ng129-recent.jpg</d:href>
            <d:propstat><d:prop><oc:favorite/></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            </d:response></d:multistatus>
        """.trimIndent()
        val url = "http://test.syrok/remote.php/dav/files/test/Photos/ng129-favorites/ng129-recent.jpg".toHttpUrl()
        assertEquals("", davProperty(xml, url, "favorite"))
    }
    @Test
    fun writeFromMainDispatcherReadsDelayedResponseBodyOffMainThread() = kotlinx.coroutines.runBlocking {
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = kotlin.concurrent.thread(isDaemon = true) {
            try { repeat(3) { index ->
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    while (input.readLine()?.isNotEmpty() == true) { }
                    val body = if (index == 0) {
                        """{"fileid":7,"filename":"/Photos/a.jpg","basename":"a.jpg","owneruid":"test","etag":"abc","permissions":"RUDS"}"""
                    } else {
                        val prop = if (index == 1) "<oc:fileid>7</oc:fileid>" else "<oc:favorite/>"
                        """<d:multistatus xmlns:d="DAV:" xmlns:oc="http://owncloud.org/ns"><d:response><d:href>/remote.php/dav/files/test/Photos/a.jpg</d:href><d:propstat><d:prop>$prop</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
                    }.toByteArray()
                    val output = socket.getOutputStream()
                    val code = if (index == 0) 200 else 207
                    output.write("HTTP/1.1 $code OK\r\nContent-Length: ${body.size}\r\nContent-Type: application/xml\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    // Headers are delivered first: a buffered response must not hide a main-thread read.
                    Thread.sleep(100)
                    output.write(body)
                    output.flush()
                }
            } } catch (_: java.io.IOException) { /* Caller failure closes the test server. */ }
        }
        try {
            val transport = com.syrok0010.nextgallery.core.network.NextcloudTransport(kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            val credentials = com.syrok0010.nextgallery.core.session.AccountCredentials("http://127.0.0.1:${server.localPort}", "test", "fixture", "test")
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                MemoriesFavoriteWriter(transport).set(credentials, 7, true)
            }
        } finally { server.close(); worker.join(1000) }
    }

}
