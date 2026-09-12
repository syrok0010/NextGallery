package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose

internal const val VideoPlaybackSurfaceTestTag = "video_playback_surface"
internal const val VideoPlaybackPlayPauseTestTag = "video_playback_play_pause"
internal const val VideoPlaybackControlsPlayPauseTestTag = "video_playback_controls_play_pause"
internal const val VideoPlaybackSeekTestTag = "video_playback_seek"
internal const val VideoPlaybackMuteTestTag = "video_playback_mute"
internal const val VideoPlaybackFullscreenTestTag = "video_playback_fullscreen"

@OptIn(UnstableApi::class)
@Composable
internal fun VideoPlaybackSurface(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onToggleChrome: () -> Unit,
    contentModifier: Modifier = Modifier.fillMaxSize(),
    controlsVisible: Boolean = true,
    onFullscreenChanged: (Boolean) -> Unit = {},
    controller: VideoPlaybackController,
) {
    val player = controller.player
    val playbackState = controller.state
    val dispatch = controller::dispatch
    val fullscreenChanged by rememberUpdatedState(onFullscreenChanged)
    val density = LocalDensity.current
    var controlsHeight by remember { mutableStateOf(0.dp) }

    DisposableEffect(controller) {
        onDispose { fullscreenChanged(false) }
    }

    BackHandler(enabled = playbackState.isFullscreen) {
        dispatch(VideoPlaybackInput.ExitFullscreen)
    }
    LaunchedEffect(playbackState.isFullscreen) {
        fullscreenChanged(playbackState.isFullscreen)
    }
    VideoFullscreenEffect(playbackState.isFullscreen)

    BoxWithConstraints(
        modifier = modifier
            .testTag(VideoPlaybackSurfaceTestTag),
    ) {
        Box(modifier = contentModifier.align(Alignment.Center).background(Color.Black)) {
            MediaAssetImage(
                item = item,
                purpose = MediaImagePurpose.DetailPreview,
                contentDescription = item.displayName,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )

            if (playbackState.phase != VideoPlaybackPhase.Poster &&
                playbackState.phase != VideoPlaybackPhase.Error
            ) {
                ContentFrame(
                    player = player,
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onToggleChrome),
        )

        if (playbackState.phase != VideoPlaybackPhase.Playing &&
            (controlsVisible || playbackState.phase in setOf(VideoPlaybackPhase.Poster, VideoPlaybackPhase.Error, VideoPlaybackPhase.Loading))
        ) VideoPlaybackCenterAction(
            phase = playbackState.phase,
            error = playbackState.error,
            onClick = {
                when (playbackState.phase) {
                    VideoPlaybackPhase.Error -> dispatch(VideoPlaybackInput.Retry)
                    VideoPlaybackPhase.Playing -> dispatch(VideoPlaybackInput.Pause)
                    else -> dispatch(VideoPlaybackInput.Play)
                }
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
