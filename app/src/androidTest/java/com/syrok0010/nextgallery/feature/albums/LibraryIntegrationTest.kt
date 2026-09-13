package com.syrok0010.nextgallery.feature.albums

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.app.ui.HomeScreen
import com.syrok0010.nextgallery.app.ui.rememberViewerTransitionCoordinator
import com.syrok0010.nextgallery.core.session.*
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.GlobalContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.Executors

/** Exercises the real HomeScreen, production DI and HTTP adapters with an isolated account. */
class LibraryIntegrationTest {
    @get:Rule val rule = createComposeRule()
    @Test fun photosAlbumsMenuAndViewerKeepTheirState() {
        val koin = GlobalContext.get()
        val sessions = koin.get<SessionStore>()
        val previousSession = sessions.session.value
        val store = ViewModelStore()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LibraryFixture().use { fixture ->
            lateinit var photos: AuthenticatedViewModel
            lateinit var albums: AlbumsViewModel
            rule.runOnUiThread {
                sessions.signIn(AccountCredentials(fixture.url, "fixture", "password"))
                photos = koin.get(); albums = koin.get()
                store.put("photos", photos); store.put("albums", albums)
            }
            try {
                rule.setContent {
                    HomeScreen(rememberViewerTransitionCoordinator(), viewModel = photos, albumsViewModel = albums)
                }
                // Permission explanation is an existing first-run flow on a fresh automation package.
                rule.waitForIdle()
                rule.onAllNodesWithText("Не сейчас").fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
                    rule.onNodeWithText("Не сейчас").performClick()
                }
                rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("fixture-1.jpg").fetchSemanticsNodes().isNotEmpty() }
                val imageResult = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                    coil3.SingletonImageLoader.get(context).execute(coil3.request.ImageRequest.Builder(context)
                        .data(com.syrok0010.nextgallery.feature.images.thumbnailRequest(AccountCredentials(fixture.url, "fixture", "password"), 1, "v1"))
                        .size(300, 300).build())
                }
                assertTrue((imageResult as? coil3.request.ErrorResult)?.throwable?.stackTraceToString().orEmpty(), imageResult is coil3.request.SuccessResult)
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
                rule.onNodeWithTag("album_card:remote:1").assertHasNoClickAction()
                screenshot("albums")
                rule.onNodeWithTag("album_catalog").performScrollToIndex(5)
                val anchor = rule.onNodeWithTag("album_card:remote:7").fetchSemanticsNode().boundsInRoot.top
                rule.onNodeWithTag("library_page:Photos").performClick()
                rule.onNodeWithContentDescription("fixture-1.jpg").assertExists()
                rule.onNodeWithTag("library_page:Albums").performClick()
                assertEquals(anchor, rule.onNodeWithTag("album_card:remote:7").fetchSemanticsNode().boundsInRoot.top, 1f)
                rule.onNodeWithTag("library_menu").performClick()
                rule.onNodeWithText("Диагностика").performClick()
                rule.onNodeWithText("/Photos/Fixture").assertExists()
                rule.onAllNodesWithText("Загружено 24 элементов").assertCountEquals(1)
                screenshot("diagnostics")
            } finally {
                rule.runOnUiThread {
                    store.clear()
                    when (previousSession) {
                        is SessionUiState.SignedIn -> sessions.signIn(previousSession.credentials)
                        else -> sessions.signOut()
                    }
                }
            }
        }
    }
    private fun screenshot(name: String) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "redesign-81").apply { mkdirs() }
        rule.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}

private class LibraryFixture : AutoCloseable {
    private val server = ServerSocket(0, 16, java.net.InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    val url = "http://127.0.0.1:${server.localPort}"
    private val jpeg = ByteArrayOutputStream().apply {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(100, 152, 171))
        canvas.drawCircle(230f, 55f, 25f, Paint().apply { color = Color.rgb(244, 217, 147) })
        canvas.drawPath(android.graphics.Path().apply { moveTo(0f, 250f); lineTo(100f, 85f); lineTo(220f, 300f); close() }, Paint().apply { color = Color.rgb(60, 88, 94) })
        canvas.drawPath(android.graphics.Path().apply { moveTo(80f, 300f); lineTo(220f, 135f); lineTo(300f, 260f); lineTo(300f, 300f); close() }, Paint().apply { color = Color.rgb(35, 69, 68) })
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this); bitmap.recycle()
    }.toByteArray()
    init {
        executor.execute {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (_: java.io.IOException) { break }
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
                                """{"album_id":$it,"name":"${it.toString().padStart(2,'0')} ${if(it==2) "Отпуск с длинным названием" else "Альбом"}","count":24,"cover":$it,"cover_etag":"v1","user_display":"Анна"}"""
                            }.toByteArray()
                            path.endsWith("/days") -> """[{"dayid":20709,"count":24,"detail":[${(1..24).joinToString { """{"fileid":$it,"dayid":20709,"w":300,"h":300,"basename":"fixture-$it.jpg","etag":"v1","epoch":1789257600,"mimetype":"image/jpeg"}""" }}]}]""".toByteArray()
                            path.contains("multipreview") -> ByteArrayOutputStream().apply {
                                val json = kotlinx.serialization.json.Json.parseToJsonElement(String(requestBody)) as kotlinx.serialization.json.JsonObject
                                val files = json["files"] as kotlinx.serialization.json.JsonArray
                                files.forEach { file ->
                                    val obj = file as kotlinx.serialization.json.JsonObject
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
