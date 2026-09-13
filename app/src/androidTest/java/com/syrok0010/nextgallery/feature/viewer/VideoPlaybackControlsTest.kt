package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertWidthIsAtLeast
import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoPlaybackControlsTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun controls_expose_seek_mute_and_fullscreen_actions() {
        var seekPosition = -1L
        var muteClicks = 0
        var fullscreenClicks = 0

        composeRule.setContent {
            MaterialTheme {
                VideoPlaybackControls(
                    state = VideoPlaybackState(
                        phase = VideoPlaybackPhase.Paused,
                        positionMillis = 1_000L,
                        durationMillis = 4_000L,
                    ),
                    onSeek = { seekPosition = it },
                    onToggleMute = { muteClicks++ },
                    onToggleFullscreen = { fullscreenClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag(VideoPlaybackSeekTestTag).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(VideoPlaybackMuteTestTag).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(VideoPlaybackFullscreenTestTag).assertIsDisplayed().performClick()

        assertEquals(1, muteClicks)
        assertEquals(1, fullscreenClicks)
        composeRule.onNodeWithTag(VideoPlaybackSeekTestTag)
            .performSemanticsAction(SemanticsActions.SetProgress) { it(0.5f) }
        assertEquals(2_000L, seekPosition)
    }

    @Test
    fun quality_menu_and_seek_fit_a_narrow_panel() {
        val original = RemoteVideoQuality("Оригинал", "https://example.com/original")
        val hd = RemoteVideoQuality("1080p", "https://example.com/1080")
        var selected: RemoteVideoQuality? = null
        composeRule.setContent {
            MaterialTheme {
                Box(Modifier.width(360.dp)) {
                    VideoPlaybackControls(
                        state = VideoPlaybackState(phase = VideoPlaybackPhase.Paused,
                            durationMillis = 42_000L, quality = original.label, qualities = listOf(original, hd)),
                        onSeek = {}, onToggleMute = {}, onToggleFullscreen = {},
                        onSelectQuality = { selected = it },
                    )
                }
            }
        }
        composeRule.onNodeWithTag(VideoPlaybackSeekTestTag).assertWidthIsAtLeast(32.dp)
        composeRule.onNodeWithTag("video_quality").assertIsDisplayed().performClick()
        composeRule.onNodeWithText("1080p").assertIsDisplayed().performClick()
        assertEquals(hd, selected)
        composeRule.onNodeWithTag(VideoPlaybackMuteTestTag).assertIsDisplayed()
        composeRule.onNodeWithTag(VideoPlaybackFullscreenTestTag).assertIsDisplayed()
    }

    @Test
    fun fullscreen_control_reflects_and_toggles_fullscreen_state() {
        var fullscreenClicks = 0

        composeRule.setContent {
            MaterialTheme {
                VideoPlaybackControls(
                    state = VideoPlaybackState(
                        phase = VideoPlaybackPhase.Paused,
                        durationMillis = 4_000L,
                        isFullscreen = true,
                    ),
                    onSeek = {},
                    onToggleMute = {},
                    onToggleFullscreen = { fullscreenClicks++ },
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Выйти из полноэкранного режима")
            .assertIsDisplayed()
            .performClick()

        assertEquals(1, fullscreenClicks)
    }
}
