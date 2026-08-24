package com.syrok0010.nextgallery.feature.viewer

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.common.MediaAssetImage
import com.syrok0010.nextgallery.ui.common.MediaImagePurpose
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

internal const val VideoPlaybackSurfaceTestTag = "video_playback_surface"
internal const val VideoPlaybackPlayPauseTestTag = "video_playback_play_pause"
internal const val VideoPlaybackControlsPlayPauseTestTag = "video_playback_controls_play_pause"
internal const val VideoPlaybackSeekTestTag = "video_playback_seek"
internal const val VideoPlaybackMuteTestTag = "video_playback_mute"
internal const val VideoPlaybackFullscreenTestTag = "video_playback_fullscreen"

@Composable
internal fun VideoPlaybackSurface(
    item: MediaItem,
    contentUri: String,
    modifier: Modifier = Modifier,
    onToggleChrome: () -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = context.videoActivity()
    val player = remember(item.mediaId) {
        ExoPlayer.Builder(context).build()
    }
    val session = remember(item.mediaId, contentUri) {
        VideoPlaybackSession(contentUri)
    }
    var playbackState by remember(item.mediaId, contentUri) {
        mutableStateOf(session.state)
    }
    val initialOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    LaunchedEffect(item.mediaId) {
        onFullscreenChanged(false)
    }

    fun applyEffect(effect: VideoPlaybackEffect?) {
        when (effect) {
            is VideoPlaybackEffect.PrepareAndPlay -> {
                player.setMediaItem(Media3Item.fromUri(effect.contentUri))
                player.prepare()
                player.playWhenReady = true
            }

            VideoPlaybackEffect.Play -> player.play()
            VideoPlaybackEffect.ReplayFromStart -> {
                player.seekTo(0L)
                player.play()
            }
            VideoPlaybackEffect.Pause -> player.pause()
            VideoPlaybackEffect.PauseAndRelease -> {
                player.pause()
                player.release()
            }
            is VideoPlaybackEffect.SeekTo -> player.seekTo(effect.positionMillis)
            is VideoPlaybackEffect.SetVolume -> player.volume = effect.volume
            null -> Unit
        }
    }

    fun dispatch(input: VideoPlaybackInput) {
        val effect = session.accept(input)
        playbackState = session.state
        applyEffect(effect)
    }

    DisposableEffect(player, session) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> dispatch(VideoPlaybackInput.PlayerBuffering)
                    Player.STATE_READY -> dispatch(
                        VideoPlaybackInput.PlayerReady(
                            player.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L,
                        ),
                    )
                    Player.STATE_ENDED -> {
                        player.duration
                            .takeUnless { it == C.TIME_UNSET }
                            ?.coerceAtLeast(0L)
                            ?.let { dispatch(VideoPlaybackInput.PlayerPositionChanged(it)) }
                        dispatch(VideoPlaybackInput.PlayerIsPlaying(false))
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                dispatch(VideoPlaybackInput.PlayerIsPlaying(isPlaying))
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                dispatch(VideoPlaybackInput.PlayerFailed)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            applyEffect(session.accept(VideoPlaybackInput.Leave))
            onFullscreenChanged(false)
        }
    }

    LaunchedEffect(player) {
        while (isActive) {
            if (player.playbackState == Player.STATE_READY) {
                dispatch(VideoPlaybackInput.PlayerPositionChanged(player.currentPosition))
            }
            delay(200L.milliseconds)
        }
    }

    LaunchedEffect(playbackState.isFullscreen, activity) {
        if (playbackState.isFullscreen) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            activity?.requestedOrientation = initialOrientation
        }
        onFullscreenChanged(playbackState.isFullscreen)
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = initialOrientation
        }
    }

    Box(
        modifier = modifier
            .testTag(VideoPlaybackSurfaceTestTag)
            .background(Color.Black),
    ) {
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
            PlayerSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(onClick = onToggleChrome),
        )

        VideoPlaybackCenterAction(
            phase = playbackState.phase,
            onClick = {
                when (playbackState.phase) {
                    VideoPlaybackPhase.Error -> dispatch(VideoPlaybackInput.Retry)
                    VideoPlaybackPhase.Playing -> dispatch(VideoPlaybackInput.Pause)
                    else -> dispatch(VideoPlaybackInput.Play)
                }
            },
            modifier = Modifier.align(Alignment.Center),
        )

        VideoPlaybackControls(
            state = playbackState,
            onPlayPause = {
                if (playbackState.phase == VideoPlaybackPhase.Playing) {
                    dispatch(VideoPlaybackInput.Pause)
                } else if (playbackState.phase != VideoPlaybackPhase.Loading) {
                    dispatch(VideoPlaybackInput.Play)
                }
            },
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
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun VideoPlaybackCenterAction(
    phase: VideoPlaybackPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (phase) {
        VideoPlaybackPhase.Loading -> {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                Text(
                    text = stringResource(R.string.video_playback_loading),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        VideoPlaybackPhase.Poster,
        VideoPlaybackPhase.Ready,
        VideoPlaybackPhase.Paused,
        VideoPlaybackPhase.Error,
        -> {
            Column(
                modifier = modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(
                    onClick = onClick,
                    modifier = Modifier.testTag(VideoPlaybackPlayPauseTestTag),
                ) {
                    Icon(
                        painter = painterResource(
                            if (phase == VideoPlaybackPhase.Error) R.drawable.ic_video_replay else R.drawable.ic_video_play,
                        ),
                        contentDescription = stringResource(
                            if (phase == VideoPlaybackPhase.Error) {
                                R.string.video_playback_retry
                            } else {
                                R.string.video_playback_play
                            },
                        ),
                        tint = Color.White,
                    )
                }
                if (phase == VideoPlaybackPhase.Error) {
                    Text(
                        text = stringResource(R.string.video_playback_error),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }

        VideoPlaybackPhase.Playing -> {
            IconButton(
                onClick = onClick,
                modifier = modifier.testTag(VideoPlaybackPlayPauseTestTag),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_video_pause),
                    contentDescription = stringResource(R.string.video_playback_pause),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
internal fun VideoPlaybackControls(
    state: VideoPlaybackState,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMillis = state.durationMillis ?: 0L
    val sliderValue = if (durationMillis > 0L) {
        state.positionMillis.coerceIn(0L, durationMillis).toFloat() / durationMillis
    } else {
        0f
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Slider(
            value = sliderValue,
            onValueChange = { value ->
                if (durationMillis > 0L) onSeek((value * durationMillis).toLong())
            },
            enabled = durationMillis > 0L && state.phase != VideoPlaybackPhase.Loading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VideoPlaybackSeekTestTag),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(
                onClick = onPlayPause,
                modifier = Modifier.testTag(VideoPlaybackControlsPlayPauseTestTag),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.phase == VideoPlaybackPhase.Playing) {
                            R.drawable.ic_video_pause
                        } else {
                            R.drawable.ic_video_play
                        },
                    ),
                    contentDescription = stringResource(
                        if (state.phase == VideoPlaybackPhase.Playing) {
                            R.string.video_playback_pause
                        } else {
                            R.string.video_playback_play
                        },
                    ),
                    tint = Color.White,
                )
            }
            Text(
                text = stringResource(
                    R.string.video_playback_position,
                    formatVideoTime(state.positionMillis),
                    formatVideoTime(durationMillis),
                ),
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = onToggleMute,
                modifier = Modifier.testTag(VideoPlaybackMuteTestTag),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isMuted) R.drawable.ic_video_volume_off else R.drawable.ic_video_volume_up,
                    ),
                    contentDescription = stringResource(
                        if (state.isMuted) R.string.video_playback_unmute else R.string.video_playback_mute,
                    ),
                    tint = Color.White,
                )
            }
            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier.testTag(VideoPlaybackFullscreenTestTag),
            ) {
                Icon(
                    painter = painterResource(
                        if (state.isFullscreen) R.drawable.ic_video_fullscreen_exit else R.drawable.ic_video_fullscreen,
                    ),
                    contentDescription = stringResource(
                        if (state.isFullscreen) {
                            R.string.video_playback_exit_fullscreen
                        } else {
                            R.string.video_playback_fullscreen
                        },
                    ),
                    tint = Color.White,
                )
            }
        }
    }
}

private fun formatVideoTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds / 60L) % 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

private fun Context.videoActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}
