package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import org.koin.core.context.GlobalContext
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.session.AccountCredentials
import android.content.pm.ActivityInfo
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaId
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class VideoPlaybackIntegrationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val inserted = mutableListOf<Uri>()

    @After fun removeSamples() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        inserted.forEach { resolver.delete(it, null, null) }
    }

    @Test fun localMp4RequiresPlaySupportsControlsAndPausesInBackground() {
        val item = sample()
        lateinit var player: ExoPlayer
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(
                    item = item,
                    modifier = Modifier.fillMaxSize(),
                    onToggleChrome = {},
                    createPlayer = { ExoPlayer.Builder(it).build().also { created -> player = created } },
                )
            }
        }
        rule.runOnIdle {
            assertFalse(player.playWhenReady)
            assertEquals(0L, player.currentPosition)
        }
        screenshot("poster")
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition > 300L }
        rule.runOnIdle { assertTrue(player.duration in 11_900L..12_100L) }
        screenshot("playing")
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { !player.playWhenReady }
        rule.onNodeWithTag(VideoPlaybackSeekTestTag).performTouchInput {
            swipe(Offset(width * 0.2f, centerY), Offset(width * 0.5f, centerY), durationMillis = 500)
        }
        // Drag coordinates include the slider touch slop; verify the gesture separately
        // from the precise seek contract, which uses the accessibility progress action.
        waitForPlayer { player.currentPosition in 5_000L..7_000L }
        screenshot("after-seek")
        rule.onNodeWithTag(VideoPlaybackSeekTestTag).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForPlayer { player.currentPosition in 5_800L..6_200L }
        rule.runOnIdle { assertEquals("Seek to half duration", 6_000.0, player.currentPosition.toDouble(), 200.0) }
        rule.onNodeWithTag(VideoPlaybackMuteTestTag).performClick()
        rule.runOnIdle { assertEquals(0f, player.volume) }
        rule.onNodeWithTag(VideoPlaybackMuteTestTag).performClick()
        rule.runOnIdle { assertEquals(1f, player.volume) }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForPlayer { !player.playWhenReady }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForPlayer { !player.isPlaying }
        rule.onNodeWithTag(VideoPlaybackSeekTestTag).performSemanticsAction(SemanticsActions.SetProgress) { it(0.97f) }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { player.playbackState == androidx.media3.common.Player.STATE_ENDED }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition < 2_000L }
    }

    @Test fun viewerOwnsOnlyCurrentSessionAndFullscreenBackKeepsCurrentItem() {
        val video = sample()
        val neighbor = video.copy(mediaId = MediaId("neighbor"), displayName = "neighbor.mp4")
        var current: MediaItem? = null
        var closed = false
        rule.runOnUiThread { rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        val initialOrientation = rule.activity.requestedOrientation
        val items = listOf(video, neighbor)
        rule.setContent {
            MaterialTheme {
                MediaDetailScreen(
                    initialMediaId = video.mediaId,
                    sequence = ViewerSequence(
                        items, items.mapIndexed { index, item -> item.mediaId to index }.toMap(),
                    ),
                    tileBoundsForMediaId = { null }, onBack = { closed = true },
                    onCurrentItemChange = { current = it },
                )
            }
        }
        rule.onAllNodesWithTag(VideoPlaybackSurfaceTestTag).assertCountEquals(1)
        screenshot("viewer-poster")
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        rule.waitUntil(10_000) {
            !rule.onNodeWithTag(VideoPlaybackSeekTestTag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        rule.onNodeWithTag(VideoPlaybackFullscreenTestTag).performClick()
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_exit_fullscreen)).assertIsDisplayed()
        screenshot("fullscreen")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitUntil(10_000) { rule.activity.requestedOrientation == initialOrientation }
        rule.waitUntil(10_000) { rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
        rule.onNodeWithTag(FilmstripTestTag).assertIsDisplayed()
        screenshot("viewer-after-fullscreen")
        rule.runOnIdle {
            assertFalse(closed)
            assertEquals(video.mediaId, current?.mediaId)
        }
        rule.onNodeWithTag(filmstripTileTestTag(1)).performClick()
        rule.onAllNodesWithTag(VideoPlaybackSurfaceTestTag).assertCountEquals(1)
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).assertIsDisplayed()
        rule.onNodeWithText("0:00 / 0:00").assertIsDisplayed()
        rule.onNodeWithTag(filmstripTileTestTag(0)).performClick()
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).assertIsDisplayed()
        rule.onNodeWithText("0:00 / 0:00").assertIsDisplayed()
    }

    @Test fun leavingSurfaceReleasesPlayerAndReturningStartsAtZero() {
        val item = sample()
        val visible = mutableStateOf(true)
        val players = mutableListOf<ExoPlayer>()
        rule.setContent {
            if (visible.value) {
                MaterialTheme {
                    VideoPlaybackSurface(
                        item,
                        Modifier.fillMaxSize(), onToggleChrome = {},
                        createPlayer = { ExoPlayer.Builder(it).build().also(players::add) },
                    )
                }
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { players.single().currentPosition > 300L }
        rule.runOnIdle { visible.value = false }
        rule.waitForIdle()
        rule.runOnIdle {
            assertTrue(players.single().isReleased)
            visible.value = true
        }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(2, players.size)
            assertFalse(players.last().playWhenReady)
            assertEquals(0L, players.last().currentPosition)
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).assertIsDisplayed()
    }

    @Test fun unreadableVideoShowsRetryAndCanRecover() {
        val item = sample(corrupt = true)
        val uri = Uri.parse((item.assetRef as MediaAssetRef.LocalContent).contentUri)
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(item, Modifier.fillMaxSize(), onToggleChrome = {})
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.video_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(rule.activity.getString(R.string.video_playback_error)).assertIsDisplayed()
        screenshot("error")
        writeSample(uri)
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_retry)).performClick()
        rule.waitUntil(10_000) {
            !rule.onNodeWithTag(VideoPlaybackSeekTestTag).fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
        }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_pause)).assertIsDisplayed()
    }

    @Test fun remoteOriginalPlaysAndPagerChangeReleasesPlayer() = withRemote { fixture, remote ->
        val players = mutableListOf<ExoPlayer>()
        val factory = GlobalContext.get().get<VideoPlayerFactory>()
        rule.setContent {
            val pagerState = rememberPagerState { 2 }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().then(Modifier.testTag("remote-pager")),
            ) { page ->
                if (page == pagerState.currentPage) MaterialTheme {
                    VideoPlaybackSurface(
                        item = remote.copy(mediaId = MediaId("remote-$page")),
                        onToggleChrome = {},
                        createPlayer = { factory.create(it).also(players::add) },
                    )
                }
            }
        }
        rule.runOnIdle { assertFalse(players.single().playWhenReady) }
        assertTrue(fixture.requests.none { it[":request"]?.contains("/stream/") == true })
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { players.single().isPlaying && players.single().currentPosition > 300 }
        rule.runOnIdle { assertTrue(players.single().duration in 11_900L..12_100L) }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        rule.onNodeWithTag(VideoPlaybackSeekTestTag).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForPlayer { players.single().currentPosition in 5_800L..6_200L }
        rule.onNodeWithTag(VideoPlaybackMuteTestTag).performClick()
        rule.runOnIdle { assertEquals(0f, players.single().volume) }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { players.single().isPlaying }
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForPlayer { !players.single().playWhenReady }
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForPlayer { !players.single().isPlaying }
        screenshot("remote-playing")
        assertTrue(fixture.requests.any { it[":request"] == "GET /apps/memories/api/stream/42 HTTP/1.1" && it["authorization"] == fixture.authorization })
        rule.onNodeWithTag("remote-pager").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(2, players.size)
            assertTrue(players.first().isReleased)
            assertFalse(players.last().playWhenReady)
            assertEquals(0L, players.last().currentPosition)
        }
        rule.onAllNodesWithTag(VideoPlaybackSurfaceTestTag).assertCountEquals(1)
    }

    @Test fun corruptLocalFallsBackToRemoteAndAuthRetryUsesNewSession() = withRemote { fixture, remote ->
        val local = sample(corrupt = true)
        val merged = local.copy(assetRef = MediaAssetRef.LocalFirst(
            local.assetRef as MediaAssetRef.LocalContent, remote.assetRef as MediaAssetRef.MemoriesFile,
        ))
        fixture.authorization = okhttp3.Credentials.basic("fixture", "renewed")
        lateinit var player: ExoPlayer
        val koin = GlobalContext.get()
        val factory = koin.get<VideoPlayerFactory>()
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(merged, onToggleChrome = {}, createPlayer = {
                    factory.create(it).also { created -> player = created }
                })
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.video_playback_auth_error)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("remote-auth-error")
        koin.get<SessionStore>().signIn(
            AccountCredentials(fixture.url, "fixture", "renewed"),
        )
        fixture.status = 404
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_retry)).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.video_playback_remote_error)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("remote-unavailable")
        fixture.status = 200
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_retry)).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition > 300 }
        rule.onAllNodesWithTag(VideoPlaybackSurfaceTestTag).assertCountEquals(1)
        screenshot("remote-fallback-playing")
        assertTrue(fixture.requests.any { it["authorization"] == fixture.authorization })
    }

    @Test fun readableLocalCopyDoesNotRequestRemoteOriginal() = withRemote { fixture, remote ->
        val local = sample()
        val merged = local.copy(assetRef = MediaAssetRef.LocalFirst(
            local.assetRef as MediaAssetRef.LocalContent, remote.assetRef as MediaAssetRef.MemoriesFile,
        ))
        lateinit var player: ExoPlayer
        val factory = GlobalContext.get().get<VideoPlayerFactory>()
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(merged, onToggleChrome = {}, createPlayer = {
                    factory.create(it).also { created -> player = created }
                })
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition > 300 }
        assertTrue(fixture.requests.none { it[":request"]?.contains("/stream/") == true })
    }

    @Test fun remoteHlsSwitchKeepsPausedPositionAndRetryRecovers() = withRemote { fixture, remote ->
        fixture.hls = true
        lateinit var player: ExoPlayer
        val factory = GlobalContext.get().get<VideoPlayerFactory>()
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(remote, onToggleChrome = {}, createPlayer = {
                    factory.create(it).also { created -> player = created }
                })
            }
        }
        assertTrue(fixture.requests.none {
            it[":request"]?.let { request -> request.contains("/stream/") || request.contains("/video/transcode/") } == true
        })
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        rule.onNodeWithTag(VideoPlaybackSeekTestTag).performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        waitForPlayer { player.currentPosition in 5800L..6200L }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("video_quality").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("video_quality").performClick()
        rule.onNodeWithText("360p").performClick()
        waitForPlayer { player.playbackState == androidx.media3.common.Player.STATE_READY && player.currentMediaItem?.localConfiguration?.uri.toString().contains("360p.m3u8") }
        rule.runOnIdle {
            assertFalse(player.playWhenReady)
            assertTrue(player.currentPosition in 5800L..6200L)
        }
        screenshot("hls-paused-quality-switch")
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition > 6500 }
        rule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        fixture.hlsStatus = 500
        rule.onNodeWithTag("video_quality").performClick()
        rule.onNodeWithText("Auto").performClick()
        rule.waitUntil(20_000) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.video_playback_remote_error)).fetchSemanticsNodes().isNotEmpty()
        }
        screenshot("hls-transcode-error")
        fixture.hlsStatus = 200
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_retry)).performClick()
        waitForPlayer { player.playbackState == androidx.media3.common.Player.STATE_READY }
        rule.runOnIdle {
            assertFalse(player.playWhenReady)
            assertTrue(player.currentPosition > 6500)
        }
        assertTrue(fixture.requests.any { it[":request"]?.contains(".ts") == true })
        assertTrue(fixture.requests.filter { it[":request"]?.contains("/video/transcode/") == true }
            .all { it["authorization"] == fixture.authorization })
    }

    @Test fun incompatibleRemoteAutomaticallyFallsBackToHls() = withRemote { fixture, remote ->
        fixture.hls = true
        fixture.corruptOriginal = true
        lateinit var player: ExoPlayer
        val factory = GlobalContext.get().get<VideoPlayerFactory>()
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(remote, onToggleChrome = {}, createPlayer = {
                    factory.create(it).also { created -> player = created }
                })
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        waitForPlayer { player.isPlaying && player.currentPosition > 300 }
        rule.runOnIdle { assertTrue(player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("index.m3u8")) }
        screenshot("hls-automatic-fallback")
    }

    @Test fun retryRediscoversHlsAfterTransientManifestFailure() = withRemote { fixture, remote ->
        fixture.hls = true
        fixture.corruptOriginal = true
        fixture.hlsStatus = 503
        lateinit var player: ExoPlayer
        val factory = GlobalContext.get().get<VideoPlayerFactory>()
        rule.setContent {
            MaterialTheme {
                VideoPlaybackSurface(remote, onToggleChrome = {}, createPlayer = {
                    factory.create(it).also { created -> player = created }
                })
            }
        }
        rule.onNodeWithTag(VideoPlaybackPlayPauseTestTag).performClick()
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(rule.activity.getString(R.string.video_playback_error)).fetchSemanticsNodes().isNotEmpty()
        }
        fixture.hlsStatus = 200
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.video_playback_retry)).performClick()
        waitForPlayer { player.isPlaying }
        rule.runOnIdle { assertTrue(player.currentMediaItem?.localConfiguration?.uri.toString().endsWith("index.m3u8")) }
    }

    private fun withRemote(block: (RemoteVideoFixture, MediaItem) -> Unit) {
        val session = GlobalContext.get().get<SessionStore>()
        val previous = session.session.value
        val bytes = InstrumentationRegistry.getInstrumentation().context.assets.open("local-video.mp4").use { it.readBytes() }
        RemoteVideoFixture(bytes).use { fixture ->
            try {
                session.signIn(AccountCredentials(fixture.url, "fixture", "password"))
                val remote = sample().copy(assetRef = MediaAssetRef.MemoriesFile(42))
                block(fixture, remote)
            } finally {
                when (previous) {
                    is SessionUiState.SignedIn -> session.signIn(previous.credentials)
                    else -> session.signOut()
                }
            }
        }
    }

    private fun waitForPlayer(predicate: () -> Boolean) {
        rule.waitUntil(10_000) {
            var result = false
            rule.runOnUiThread { result = predicate() }
            result
        }
    }

    private fun sample(corrupt: Boolean = false): MediaItem {
        val resolver = rule.activity.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "nextgallery-test-${System.nanoTime()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/NextGalleryTests")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }))
        inserted += uri
        if (corrupt) resolver.openOutputStream(uri, "w")!!.use { it.write(byteArrayOf(1, 2, 3)) }
        else writeSample(uri)
        resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        return MediaItem(
            mediaId = MediaId("test-video"), dayId = 20_000,
            displayName = "Local H.264 + AAC.mp4", mimeType = "video/mp4",
            width = 640, height = 360, etag = null, livePhotoId = null, auid = null, buid = null,
            sharedBy = null, takenAtEpochSeconds = null, isVideo = true, videoDurationSeconds = 12,
            isFavorite = false, isHidden = false, assetRef = MediaAssetRef.LocalContent(uri.toString(), 123),
        )
    }

    private fun writeSample(uri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.context.assets.open("local-video.mp4").use { input ->
            instrumentation.targetContext.contentResolver.openOutputStream(uri, "wt")!!.use { input.copyTo(it) }
        }
    }

    private fun screenshot(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        val directory = File(rule.activity.getExternalFilesDir(null), "video-review").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
