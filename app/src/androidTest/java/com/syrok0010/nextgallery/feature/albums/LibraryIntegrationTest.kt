package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.string
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import com.syrok0010.nextgallery.core.network.loopbackServer
import com.syrok0010.nextgallery.core.network.origin
import java.io.ByteArrayOutputStream
import mockwebserver3.MockResponse
import okio.Buffer
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
                sessions.signIn(AccountCredentials(fixture.url, "fixture", "password", "fixture"))
            }
            try {
                val restoration = StateRestorationTester(rule)
                restoration.setContent {
                    NextGalleryApp()
                }
                // Permission explanation is an existing first-run flow on a fresh automation package.
                rule.waitForIdle()
                rule.onAllNodesWithText(string(R.string.action_not_now)).fetchSemanticsNodes().takeIf { it.isNotEmpty() }?.let {
                    rule.onNodeWithText(string(R.string.action_not_now)).performClick()
                }
                rule.waitUntil(15_000) { rule.onAllNodesWithContentDescription("fixture-1.jpg").fetchSemanticsNodes().isNotEmpty() }
                val imageResult = runBlocking(Dispatchers.IO) {
                    SingletonImageLoader.get(context).execute(ImageRequest.Builder(context)
                        .data(thumbnailRequest(AccountCredentials(fixture.url, "fixture", "password", "fixture"), 1, "v1"))
                        .size(300, 300).build())
                }
                assertTrue((imageResult as? ErrorResult)?.throwable?.stackTraceToString().orEmpty(), imageResult is SuccessResult)
                // The tile shows the decoded thumbnail, not its placeholder: the fixture's centre is dark green.
                rule.waitUntil(15_000) {
                    val pixels = rule.onNodeWithContentDescription("fixture-1.jpg").captureToImage().toPixelMap()
                    pixels[pixels.width / 2, pixels.height / 2].green > pixels[pixels.width / 2, pixels.height / 2].red * 1.2f
                }
                rule.onNodeWithContentDescription("fixture-1.jpg").performClick()
                rule.onNodeWithTag("library_island").assertDoesNotExist()
                rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
                rule.onNodeWithTag("library_island").assertIsDisplayed()
                rule.onNodeWithTag("library_page:Albums").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithTag("album_card:remote:1").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("album_card:remote:1").assertHasClickAction()
                rule.onNodeWithTag("album_card:remote:1").performClick()
                rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("album-160.jpg").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("library_island").assertDoesNotExist()
                rule.onNodeWithContentDescription("fixture-1.jpg").assertDoesNotExist()
                rule.onNodeWithTag("media_grid").performScrollToIndex(41)
                val albumAnchor = rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top
                restoration.emulateSavedInstanceStateRestore()
                assertEquals(albumAnchor, rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top, 1f)
                rule.onNodeWithContentDescription("album-120.jpg").performClick()
                restoration.emulateSavedInstanceStateRestore()
                rule.onNodeWithText("album-120.jpg").assertIsDisplayed()
                rule.onNodeWithContentDescription(string(R.string.action_back)).performClick()
                rule.onNodeWithTag("media_grid").assertIsDisplayed()
                assertEquals(albumAnchor, rule.onNodeWithContentDescription("album-120.jpg").fetchSemanticsNode().boundsInRoot.top, 1f)
                Espresso.pressBack()
                rule.onNodeWithTag("album_catalog").assertIsDisplayed()
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
}

private class LibraryFixture : AutoCloseable {
    private val jpeg = ByteArrayOutputStream().apply {
        val bitmap = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.rgb(100, 152, 171))
        canvas.drawCircle(230f, 55f, 25f, Paint().apply { color = Color.rgb(244, 217, 147) })
        canvas.drawPath(Path().apply { moveTo(0f, 250f); lineTo(100f, 85f); lineTo(220f, 300f); close() }, Paint().apply { color = Color.rgb(60, 88, 94) })
        canvas.drawPath(Path().apply { moveTo(80f, 300f); lineTo(220f, 135f); lineTo(300f, 260f); lineTo(300f, 300f); close() }, Paint().apply { color = Color.rgb(35, 69, 68) })
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this); bitmap.recycle()
    }.toByteArray()
    private val server = loopbackServer { request ->
        val path = request.target
        val body = when {
            path.endsWith("/config") -> """{"version":"fixture","albums_enabled":true,"timeline_path":"/Photos/Fixture"}""".toByteArray()
            path.endsWith("/clusters/albums") -> (1..16).joinToString(prefix = "[", postfix = "]") {
                """{"album_id":$it,"name":"${it.toString().padStart(2,'0')} ${if(it==2) "Отпуск с длинным названием" else "Альбом"}","count":24,"cover":$it,"cover_etag":"v1","user":"anna","user_display":"Анна"}"""
            }.toByteArray()
            path.contains("/days?albums=") -> """[{"dayid":20709,"count":60,"detail":[${(101..160).joinToString { """{"fileid":$it,"dayid":20709,"w":300,"h":300,"basename":"album-$it.jpg","etag":"v1","epoch":${1789257600 + it},"mimetype":"image/jpeg"}""" }}]}]""".toByteArray()
            path.endsWith("/days") -> """[{"dayid":20709,"count":24,"detail":[${(1..24).joinToString { """{"fileid":$it,"dayid":20709,"w":300,"h":300,"basename":"fixture-$it.jpg","etag":"v1","epoch":1789257600,"mimetype":"image/jpeg"}""" }}]}]""".toByteArray()
            path.contains("multipreview") -> ByteArrayOutputStream().apply {
                val json = Json.parseToJsonElement(request.body?.utf8().orEmpty()) as JsonObject
                val files = json["files"] as JsonArray
                // fixture-1 has no generated preview yet, like a just uploaded file:
                // multipreview skips it and the app asks image/preview for it.
                files.filter { (it as JsonObject)["fileid"].toString() != "1" }.forEach { file ->
                    val obj = file as JsonObject
                    val header = """{"reqid":${obj["reqid"]},"len":${jpeg.size},"type":"image/jpeg"}""".toByteArray()
                    write(header.size); write(header); write(jpeg)
                }
            }.toByteArray()
            else -> jpeg
        }
        val type = if (path.contains("image/preview") || path.contains("stream/")) "image/jpeg" else "application/json"
        MockResponse.Builder().addHeader("Content-Type", type).body(Buffer().write(body)).build()
    }
    val url = server.origin
    override fun close() { server.close() }
}
