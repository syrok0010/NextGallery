package com.syrok0010.nextgallery.feature.albums

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.syrok0010.nextgallery.app.ui.NextGalleryApp
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.images.thumbnailRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext

/** Exercises the real NextGalleryApp, production DI and HTTP adapters with an isolated account. */
class LibraryIntegrationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun photosAlbumsMenuAndViewerKeepTheirState() {
        val koin = GlobalContext.get()
        val sessions = koin.get<SessionStore>()
        val previousSession = sessions.session.value
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LibraryFixture().use { fixture ->
            rule.runOnUiThread {
                sessions.signIn(AccountCredentials(fixture.url, "fixture", "password"))
            }
            try {
                val restoration = StateRestorationTester(rule)
                restoration.setContent {
                    NextGalleryApp()
                }
                // Permission explanation is an existing first-run flow on a fresh automation package.
                rule.waitForIdle()
                rule.onAllNodesWithText("Не сейчас").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
                    rule.onNodeWithText("Не сейчас").performClick()
                }
                rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("fixture-1.jpg").fetchSemanticsNodes().isNotEmpty() }
                val imageResult = runBlocking(Dispatchers.IO) {
                    SingletonImageLoader.get(context).execute(ImageRequest.Builder(context)
                        .data(thumbnailRequest(AccountCredentials(fixture.url, "fixture", "password"), 1, "v1"))
                        .size(300, 300).build())
                }
                assertTrue((imageResult as? ErrorResult)?.throwable?.stackTraceToString().orEmpty(), imageResult is SuccessResult)
                rule.waitUntil(15_000) {
                    val pixels = rule.onNodeWithContentDescription("fixture-1.jpg").captureToImage().toPixelMap()
                    pixels[pixels.width / 2, pixels.height / 2].green > pixels[pixels.width / 2, pixels.height / 2].red * 1.2f
                }
                screenshot("photos")
                rule.onNodeWithContentDescription("fixture-1.jpg").performClick()
                rule.onNodeWithTag("library_island").assertDoesNotExist()
                rule.onNodeWithContentDescription("Назад").performClick()
                rule.onNodeWithTag("library_island").assertIsDisplayed()
                rule.onNodeWithTag("library_page:Albums").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("album_card:remote:1").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("album_card:remote:1").assertHasClickAction()
                rule.onNodeWithTag("album_card:remote:1").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("album-160.jpg").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("library_island").assertDoesNotExist()
                rule.onNodeWithContentDescription("fixture-1.jpg").assertDoesNotExist()
                rule.onNodeWithTag("album_contents_grid").performScrollToIndex(40)
                val albumAnchor = rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top
                restoration.emulateSavedInstanceStateRestore()
                assertEquals(albumAnchor, rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top, 1f)
                screenshot("album-contents")
                rule.onNodeWithContentDescription("album-120.jpg").performClick()
                restoration.emulateSavedInstanceStateRestore()
                rule.onNodeWithText("album-120.jpg").assertIsDisplayed()
                rule.onNodeWithContentDescription("Назад").performClick()
                rule.onNodeWithTag("album_contents_grid").assertIsDisplayed()
                assertEquals(albumAnchor, rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top, 1f)
                Espresso.pressBack()
                rule.onNodeWithTag("album_catalog").assertIsDisplayed()
                screenshot("albums")
                rule.onNodeWithTag("album_catalog").performScrollToIndex(5)
                val anchor = rule.onNodeWithTag("album_card:remote:7").fetchSemanticsNode().boundsInRoot.top
                restoration.emulateSavedInstanceStateRestore()
                rule.onNodeWithTag("library_page:Albums").assertIsSelected()
                assertEquals(anchor, rule.onNodeWithTag("album_card:remote:7").fetchSemanticsNode().boundsInRoot.top, 1f)
                // Reselecting an active destination must not add a duplicate entry.
                rule.onNodeWithTag("library_page:Albums").performClick()
                Espresso.pressBack()
                rule.onNodeWithTag("library_page:Photos").assertIsSelected()
                rule.onNodeWithContentDescription("fixture-1.jpg").assertExists()
                rule.onNodeWithTag("library_page:Albums").performClick()
                assertEquals(anchor, rule.onNodeWithTag("album_card:remote:7").fetchSemanticsNode().boundsInRoot.top, 1f)
                rule.onNodeWithTag("library_menu").performClick()
                rule.onNodeWithText("Диагностика").performClick()
                rule.onNodeWithText("/Photos/Fixture").assertExists()
                rule.onAllNodesWithText("Загружено 24 элементов").assertCountEquals(1)
                screenshot("diagnostics")
                rule.runOnUiThread { sessions.signOut() }
                rule.onNodeWithText("Подключение к Nextcloud").assertIsDisplayed()
                rule.onNodeWithTag("library_island").assertDoesNotExist()
                rule.onNodeWithTag("album_catalog").assertDoesNotExist()
                rule.runOnUiThread { sessions.signIn(AccountCredentials(fixture.url, "fixture", "password")) }
                rule.onNodeWithTag("library_page:Photos").assertIsSelected()
                rule.onNodeWithTag("library_diagnostics").assertDoesNotExist()
            } finally {
                rule.runOnUiThread {
                    when (previousSession) {
                        is SessionUiState.SignedIn -> sessions.signIn(previousSession.credentials)
                        else -> sessions.signOut()
                    }
                }
            }
        }
    }
    private fun screenshot(name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val directory = (output?.let(::File)
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "redesign-81"))
            .apply { mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

private class LibraryFixture : AutoCloseable {
    private val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${server.localPort}"
    private val jpeg = ByteArrayOutputStream().apply {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(100, 152, 171))
        canvas.drawCircle(230f, 55f, 25f, Paint().apply { color = Color.rgb(244, 217, 147) })
        canvas.drawPath(Path().apply { moveTo(0f, 250f); lineTo(100f, 85f); lineTo(220f, 300f); close() }, Paint().apply { color = Color.rgb(60, 88, 94) })
        canvas.drawPath(Path().apply { moveTo(80f, 300f); lineTo(220f, 135f); lineTo(300f, 260f); lineTo(300f, 300f); close() }, Paint().apply { color = Color.rgb(35, 69, 68) })
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this); bitmap.recycle()
    }.toByteArray()
    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: IOException) { break }
                executor.execute {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val first = reader.readLine() ?: return@use
                        val headers = generateSequence { reader.readLine()?.takeIf(String::isNotEmpty) }.toList()
                        val path = first.split(' ')[1]
                        val length = headers.firstOrNull { h -> h.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toInt() ?: 0
                        val requestBody = CharArray(length)
                        var offset = 0
                        while (offset < length) { val count = reader.read(requestBody, offset, length - offset); if (count < 0) break; offset += count }
                        val body = when {
                            path.endsWith("/config") -> """{"version":"fixture","albums_enabled":true,"timeline_path":"/Photos/Fixture"}""".toByteArray()
                            path.endsWith("/clusters/albums") -> (1..16).joinToString(prefix = "[", postfix = "]") {
                                """{"album_id":$it,"name":"${it.toString().padStart(2,'0')} ${if(it==2) "Отпуск с длинным названием" else "Альбом"}","count":24,"cover":$it,"cover_etag":"v1","user":"anna","user_display":"Анна"}"""
                            }.toByteArray()
                            path.contains("/days?albums=") -> """[{"dayid":20709,"count":60,"detail":[${(101..160).joinToString { """{"fileid":$it,"dayid":20709,"w":300,"h":300,"basename":"album-$it.jpg","etag":"v1","epoch":${1789257600 + it},"mimetype":"image/jpeg"}""" }}]}]""".toByteArray()
                            path.endsWith("/days") -> """[{"dayid":20709,"count":24,"detail":[${(1..24).joinToString { """{"fileid":$it,"dayid":20709,"w":300,"h":300,"basename":"fixture-$it.jpg","etag":"v1","epoch":1789257600,"mimetype":"image/jpeg"}""" }}]}]""".toByteArray()
                            path.contains("multipreview") -> ByteArrayOutputStream().apply {
                                val json = Json.parseToJsonElement(String(requestBody)) as JsonObject
                                val files = json["files"] as JsonArray
                                files.forEach { file ->
                                    val obj = file as JsonObject
                                    val header = """{"reqid":${obj["reqid"]},"len":${jpeg.size},"type":"image/jpeg"}""".toByteArray()
                                    write(header.size); write(header); write(jpeg)
                                }
                            }.toByteArray()
                            else -> jpeg
                        }
                        it.getOutputStream().apply {
                            write("HTTP/1.1 200 OK\r\nContent-Type: ${if (path.contains("image/preview") || path.contains("stream/")) "image/jpeg" else "application/json"}\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                    }
                }
            }
        }
    }
    override fun close() { server.close(); executor.shutdownNow() }
}
