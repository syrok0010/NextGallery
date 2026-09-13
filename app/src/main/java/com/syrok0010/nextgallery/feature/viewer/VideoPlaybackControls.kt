package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale

internal const val VideoPlaybackPlayPauseTestTag = "video_playback_play_pause"
internal const val VideoPlaybackSeekTestTag = "video_playback_seek"
internal const val VideoPlaybackMuteTestTag = "video_playback_mute"
internal const val VideoPlaybackFullscreenTestTag = "video_playback_fullscreen"

@Composable
internal fun VideoPlaybackCenterAction(
    phase: VideoPlaybackPhase,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    error: VideoPlaybackError? = null,
    showsPauseAction: Boolean = phase == VideoPlaybackPhase.Playing,
) {
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
            if (phase == VideoPlaybackPhase.Loading) {
                CircularProgressIndicator(Modifier.size(60.dp), color = Color.White, strokeWidth = 2.dp)
            }
            Icon(
                painter = painterResource(
                    if (phase == VideoPlaybackPhase.Error) R.drawable.ic_video_replay else if (showsPauseAction) R.drawable.ic_video_pause else R.drawable.ic_video_play,
                ),
                contentDescription = stringResource(
                    if (phase == VideoPlaybackPhase.Error) {
                        R.string.video_playback_retry
                    } else if (showsPauseAction) {
                        R.string.video_playback_pause
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
                    VideoPlaybackError.TranscodeFailed -> R.string.video_playback_transcode_error
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

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun VideoPlaybackControls(
    state: VideoPlaybackState,
    onSeek: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier,
    onSelectQuality: (RemoteVideoQuality) -> Unit = {},
) {
    val durationMillis = state.durationMillis ?: 0L
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val seekDescription = stringResource(R.string.video_playback_seek)
    val sliderValue = if (durationMillis > 0L) {
        state.positionMillis.coerceIn(0L, durationMillis).toFloat() / durationMillis
    } else {
        0f
    }

    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Box(Modifier.fillMaxWidth().height(36.dp).align(Alignment.Center)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(18.dp)))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.video_playback_position,
                    formatVideoTime(scrubFraction?.let { (it * durationMillis).toLong() } ?: state.positionMillis),
                    formatVideoTime(durationMillis)),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
            )
            val enabled = durationMillis > 0L && state.phase in setOf(VideoPlaybackPhase.Playing, VideoPlaybackPhase.Paused)
            Slider(
                value = scrubFraction ?: sliderValue,
                onValueChange = { scrubFraction = it },
                onValueChangeFinished = {
                    scrubFraction?.let { onSeek((it * durationMillis).toLong()) }
                    scrubFraction = null
                },
                enabled = enabled,
                thumb = {
                    Box(Modifier.size(width = 8.dp, height = 48.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).background(
                            Color.White.copy(alpha = if (enabled) 1f else 0.38f), CircleShape))
                    }
                },
                track = { slider ->
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f), CircleShape)) {
                        Box(Modifier.fillMaxWidth(slider.value.coerceIn(0f, 1f)).height(3.dp)
                            .background(Color.White.copy(alpha = if (enabled) 1f else 0.38f), CircleShape))
                    }
                },
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    .testTag(VideoPlaybackSeekTestTag).semantics { contentDescription = seekDescription },
            )
            if (state.qualities.isNotEmpty()) Box {
                var expanded by remember { mutableStateOf(false) }
                TextButton(
                    onClick = { expanded = true },
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    modifier = Modifier.testTag("video_quality").widthIn(max = 88.dp),
                ) {
                    Text(state.quality, color = Color.White, style = MaterialTheme.typography.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(12.dp))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                    shape = RoundedCornerShape(16.dp), containerColor = Color(0xF5202528)) {
                    state.qualities.forEach { quality ->
                        DropdownMenuItem(
                            text = { Text(quality.label, color = Color.White) },
                            trailingIcon = {
                                if (state.quality == quality.label) Icon(Icons.Default.Check, null, tint = Color.White)
                            },
                            onClick = { expanded = false; onSelectQuality(quality) },
                        )
                    }
                }
            }
            IconButton(onClick = onToggleMute,
                modifier = Modifier.width(40.dp).testTag(VideoPlaybackMuteTestTag)) {
                Icon(painterResource(if (state.isMuted) R.drawable.ic_video_volume_off else R.drawable.ic_video_volume_up),
                    stringResource(if (state.isMuted) R.string.video_playback_unmute else R.string.video_playback_mute),
                    tint = Color.White, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onToggleFullscreen,
                modifier = Modifier.width(40.dp).testTag(VideoPlaybackFullscreenTestTag)) {
                Icon(painterResource(if (state.isFullscreen) R.drawable.ic_video_fullscreen_exit else R.drawable.ic_video_fullscreen),
                    stringResource(if (state.isFullscreen) R.string.video_playback_exit_fullscreen else R.string.video_playback_fullscreen),
                    tint = Color.White, modifier = Modifier.size(18.dp))
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
