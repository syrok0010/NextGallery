package com.syrok0010.nextgallery.feature.viewer

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.CancellationException

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
    onCollapse: () -> Unit = {},
) {
    val context = LocalContext.current
    val provider = frameProvider ?: remember(context) { LocalVideoFrames(context) }
    val projection = remember(item.mediaId, sourceUri) { VideoFilmstripProjection<Bitmap>() }
    var state by remember(projection) { mutableStateOf(projection.state) }
    var retry by remember(projection) { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fraction = if (scroll.maxValue > 0) scroll.value.toFloat() / scroll.maxValue else 0f
    val scrub by rememberUpdatedState(onScrub)
    val finish by rememberUpdatedState(onScrubFinished)
    val label = stringResource(R.string.video_filmstrip_description)
    val phaseLabel = stringResource(when (state.phase) {
        VideoFilmstripPhase.Loading -> R.string.video_filmstrip_loading
        VideoFilmstripPhase.Ready -> R.string.video_filmstrip_ready
        VideoFilmstripPhase.Degraded -> R.string.video_filmstrip_degraded
    })

    LaunchedEffect(projection, provider, retry) {
        projection.retry()
        state = projection.state
        try {
            provider.frames(sourceUri).collect { event ->
                when (event) {
                    is VideoFrameEvent.Duration -> projection.durationKnown(event.millis)
                    is VideoFrameEvent.Frame -> projection.frameReady(event.index, event.bitmap)
                }
                state = projection.state
            }
            projection.finished()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: java.io.IOException) {
            projection.failed()
        } catch (_: RuntimeException) {
            projection.failed()
        }
        state = projection.state
    }
    DisposableEffect(projection) { onDispose { finish() } }

    LaunchedEffect(projection, scroll) {
        var previous = scroll.value
        var moving = false
        snapshotFlow { scroll.value to scroll.isScrollInProgress }.collect { (offset, inProgress) ->
            if (offset != previous || inProgress || moving) {
                val progress = if (scroll.maxValue > 0) offset.toFloat() / scroll.maxValue else 0f
                projection.scrub(progress, SystemClock.uptimeMillis(), finished = !inProgress)?.let {
                    scrub(it, !inProgress)
                }
                if (!inProgress) finish()
            }
            previous = offset
            moving = inProgress
        }
    }

    BoxWithConstraints(modifier) {
        val viewportWidth = maxWidth
        Row(
            Modifier.fillMaxSize()
                .testTag(VideoFilmstripTestTag)
                .semantics {
                    contentDescription = label
                    stateDescription = phaseLabel
                    progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                    setProgress { value ->
                        if (!value.isFinite() || state.positionsMillis.isEmpty()) false else {
                            scope.launch { scroll.scrollTo((scroll.maxValue * value.coerceIn(0f, 1f)).toInt()) }
                            true
                        }
                    }
                }
                .horizontalScroll(scroll),
        ) {
            Spacer(Modifier.width(viewportWidth / 2))
            Row(Modifier.width(viewportWidth * VideoFilmstripScreenWidths).fillMaxHeight()) {
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
            Spacer(Modifier.width(viewportWidth / 2))
        }
        Canvas(Modifier.fillMaxSize()) {
            val x = size.width / 2
            drawLine(Color.White, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
        }
        TextButton(onClick = onCollapse, modifier = Modifier.align(Alignment.BottomEnd)
            .background(Color.Black.copy(alpha = 0.8f)).testTag("video_filmstrip_collapse")) {
            Text(stringResource(R.string.video_filmstrip_collapse), color = Color.White)
        }
        if (state.phase == VideoFilmstripPhase.Degraded) {
            TextButton(onClick = { retry++ }, modifier = Modifier.align(Alignment.TopEnd)
                .background(Color.Black.copy(alpha = 0.8f)).testTag(VideoFilmstripRetryTestTag)) {
                Text(stringResource(R.string.video_filmstrip_retry), color = Color.White)
            }
        } else if (state.phase == VideoFilmstripPhase.Loading) {
            Text(phaseLabel, color = Color.White, modifier = Modifier.align(Alignment.TopStart)
                .background(Color.Black.copy(alpha = 0.7f)).padding(4.dp))
        }
    }
}
