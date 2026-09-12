package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

@Composable
internal fun VideoPlaybackChrome(controller: VideoPlaybackController, controlsVisible: Boolean) {
    val playbackState = controller.state
    val dispatch = controller::dispatch
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (playbackState.phase != VideoPlaybackPhase.Playing &&
            (controlsVisible || playbackState.phase in setOf(VideoPlaybackPhase.Poster, VideoPlaybackPhase.Error, VideoPlaybackPhase.Loading))
        ) VideoPlaybackCenterAction(
            phase = playbackState.phase,
            error = playbackState.error,
            onClick = {
                dispatch(if (playbackState.phase == VideoPlaybackPhase.Error) VideoPlaybackInput.Retry else VideoPlaybackInput.Play)
            },
            modifier = Modifier.align(Alignment.Center)
                .offset {
                    val y = if (controlsVisible) minOf(0.dp, maxHeight / 2 - controlsHeight - 64.dp) else 0.dp
                    IntOffset(0, y.roundToPx())
                },
        )

        if (controlsVisible) VideoPlaybackControls(
            state = playbackState,
            onPlayPause = {
                if (playbackState.showsPauseAction) {
                    dispatch(VideoPlaybackInput.Pause)
                } else {
                    dispatch(VideoPlaybackInput.Play)
                }
            },
            onSelectQuality = controller::selectQuality,
            onSeek = { positionMillis -> dispatch(VideoPlaybackInput.SeekTo(positionMillis)) },
            onToggleMute = { dispatch(VideoPlaybackInput.ToggleMute) },
            onToggleFullscreen = {
                dispatch(
                    if (playbackState.isFullscreen) {
                        VideoPlaybackInput.ExitFullscreen
                    } else {
                        VideoPlaybackInput.EnterFullscreen
                    },
                )
            },
            modifier = Modifier.align(Alignment.BottomCenter)
                .onSizeChanged { controlsHeight = with(density) { it.height.toDp() } }
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (playbackState.isFullscreen) 0.dp else FilmstripRowHeight),
        )
    }
}
