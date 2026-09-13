package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import java.util.Locale

@Composable
internal fun VideoPlaybackCenterAction(
    phase: VideoPlaybackPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: VideoPlaybackError? = null,
) {
    when (phase) {
        VideoPlaybackPhase.Loading -> {
            Column(
                modifier = modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color.White)
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
                    modifier = Modifier.size(64.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                        .testTag(VideoPlaybackPlayPauseTestTag),
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
                        text = stringResource(when (error) {
                            VideoPlaybackError.AuthenticationRequired -> R.string.video_playback_auth_error
                            VideoPlaybackError.RemoteUnavailable -> R.string.video_playback_remote_error
                            else -> R.string.video_playback_error
                        }),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }

        VideoPlaybackPhase.Playing -> Unit
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
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val seekDescription = stringResource(R.string.video_playback_seek)
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
            value = scrubFraction ?: sliderValue,
            onValueChange = { scrubFraction = it },
            onValueChangeFinished = {
                scrubFraction?.let { fraction -> onSeek((fraction * durationMillis).toLong()) }
                scrubFraction = null
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.28f),
                disabledThumbColor = Color.White.copy(alpha = 0.38f),
                disabledActiveTrackColor = Color.White.copy(alpha = 0.38f),
                disabledInactiveTrackColor = Color.White.copy(alpha = 0.18f),
            ),
            enabled = durationMillis > 0L && state.phase in setOf(VideoPlaybackPhase.Ready, VideoPlaybackPhase.Playing, VideoPlaybackPhase.Paused),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(VideoPlaybackSeekTestTag)
                .semantics { contentDescription = seekDescription },
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
                        if (state.showsPauseAction) {
                            R.drawable.ic_video_pause
                        } else {
                            R.drawable.ic_video_play
                        },
                    ),
                    contentDescription = stringResource(
                        if (state.showsPauseAction) {
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
                    formatVideoTime(scrubFraction?.let { (it * durationMillis).toLong() } ?: state.positionMillis),
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
