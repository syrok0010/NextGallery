package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.material3.MaterialTheme
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
        var playPauseClicks = 0
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
                    onPlayPause = { playPauseClicks++ },
                    onSeek = { seekPosition = it },
                    onToggleMute = { muteClicks++ },
                    onToggleFullscreen = { fullscreenClicks++ },
                )
            }
        }

        composeRule.onNodeWithTag(VideoPlaybackSeekTestTag).assertIsDisplayed().assertIsEnabled()
        composeRule.onNodeWithTag(VideoPlaybackControlsPlayPauseTestTag).performClick()
        composeRule.onNodeWithTag(VideoPlaybackMuteTestTag).assertIsDisplayed().performClick()
        composeRule.onNodeWithTag(VideoPlaybackFullscreenTestTag).assertIsDisplayed().performClick()

        assertEquals(1, muteClicks)
        assertEquals(1, fullscreenClicks)
        assertEquals(1, playPauseClicks)
        assertEquals(-1L, seekPosition)
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
                    onPlayPause = {},
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
