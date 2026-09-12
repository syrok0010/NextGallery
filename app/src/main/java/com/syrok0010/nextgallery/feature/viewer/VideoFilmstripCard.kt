package com.syrok0010.nextgallery.feature.viewer

import android.os.SystemClock
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import org.koin.compose.koinInject

internal const val VideoFilmstripScreenWidths = 2f

internal const val VideoFilmstripTestTag = "video_filmstrip_frames"
internal const val VideoFilmstripRetryTestTag = "video_filmstrip_retry"

@Composable
internal fun VideoFilmstripCard(
    item: MediaItem,
    sourceUri: String,
    onScrub: (Long, Boolean) -> Unit,
    onScrubFinished: () -> Unit,
    modifier: Modifier = Modifier,
    frameProvider: VideoFrameProvider? = null,
    fallbackUri: String? = null,
    fraction: Float = 0f,
    isScrolling: Boolean = false,
    onSeekFraction: (Float) -> Unit = {},
) {
    val factory: VideoFramesFactory? = if (frameProvider == null) koinInject() else null
    val provider = remember(item.mediaId, factory, frameProvider, fallbackUri) {
        frameProvider ?: checkNotNull(factory).create(fallbackUri)
    }
    val scope = rememberCoroutineScope()
    val loader = remember(item.mediaId, sourceUri, provider) { VideoFilmstripLoader(provider, sourceUri, scope) }
    val state = loader.state
    val scrub by rememberUpdatedState(onScrub)
    val finish by rememberUpdatedState(onScrubFinished)
    val label = stringResource(R.string.video_filmstrip_description)
    val phaseLabel = stringResource(when (state.phase) {
        VideoFilmstripPhase.Loading -> R.string.video_filmstrip_loading
        VideoFilmstripPhase.Ready -> R.string.video_filmstrip_ready
        VideoFilmstripPhase.Degraded -> R.string.video_filmstrip_degraded
    })

    DisposableEffect(loader) {
        loader.retry()
        onDispose { loader.close(); finish() }
    }
    LaunchedEffect(loader, fraction, isScrolling) {
        loader.scroll(fraction, isScrolling, SystemClock.uptimeMillis(), scrub, finish)
    }

    Box(modifier) {
        Row(Modifier.fillMaxSize().testTag(VideoFilmstripTestTag).semantics {
            contentDescription = label
            stateDescription = phaseLabel
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { value ->
                loader.seek(value, SystemClock.uptimeMillis())?.let {
                    onSeekFraction(value.coerceIn(0f, 1f))
                    scrub(it, true)
                    true
                } ?: false
            }
        }) {
            repeat(VideoFilmstripProjection.FrameCount) { index ->
                val frame = state.frames[index]
                val cell = Modifier.weight(1f).fillMaxHeight()
                if (frame == null) MediaAssetImage(
                    item = item, purpose = MediaImagePurpose.TimelineThumbnail,
                    contentDescription = null, modifier = cell, contentScale = ContentScale.Crop,
                ) else Image(frame.asImageBitmap(), contentDescription = null,
                    modifier = cell, contentScale = ContentScale.Crop)
            }
        }
        if (state.phase == VideoFilmstripPhase.Degraded) {
            TextButton(onClick = loader::retry, modifier = Modifier.align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.8f)).testTag(VideoFilmstripRetryTestTag)) {
                Text(stringResource(R.string.video_filmstrip_retry), color = Color.White)
            }
        } else if (state.phase == VideoFilmstripPhase.Loading) {
            LinearProgressIndicator(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp),
                color = Color.White.copy(alpha = 0.8f),
                trackColor = Color.Black.copy(alpha = 0.25f),
            )
        }
    }
}
