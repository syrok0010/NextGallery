package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasLocalCopy
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The details sheet pulled up from under the photo. [progress] is 0 when closed and 1 when open;
 * a drag past either end stretches with resistance instead of stopping hard.
 */
@Stable
internal class ViewerDetailsState(private val scope: CoroutineScope) {
    private val raw = Animatable(0f)
    var sheetHeightPx by mutableFloatStateOf(0f)

    /** The last settled target: back and taps on the photo close an open sheet first. */
    var isOpen by mutableStateOf(false)
        private set

    val progress: Float
        get() = raw.value.let {
            when {
                it > 1f -> 1f + (it - 1f) * OVERSCROLL_RESISTANCE
                it < 0f -> it * OVERSCROLL_RESISTANCE
                else -> it
            }
        }

    val engaged: Boolean get() = isOpen || raw.value != 0f

    fun dragBy(deltaPx: Float) {
        if (sheetHeightPx <= 0f) return
        val next = (raw.value - deltaPx / sheetHeightPx).coerceIn(
            -OVERSCROLL_LIMIT,
            1f + OVERSCROLL_LIMIT,
        )
        scope.launch { raw.snapTo(next) }
    }

    /** [velocityPx] is the vertical drag velocity: negative flings up and opens. */
    fun settle(velocityPx: Float, flingThresholdPx: Float) {
        val open = if (abs(velocityPx) > flingThresholdPx) velocityPx < 0f else raw.value > 0.5f
        animateTo(open, if (sheetHeightPx > 0f) -velocityPx / sheetHeightPx else 0f)
    }

    fun close() = animateTo(false, 0f)

    private fun animateTo(open: Boolean, velocity: Float) {
        isOpen = open
        scope.launch {
            raw.animateTo(
                targetValue = if (open) 1f else 0f,
                animationSpec = spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow),
                initialVelocity = velocity,
            )
        }
    }

    private companion object {
        const val OVERSCROLL_RESISTANCE = 0.15f
        const val OVERSCROLL_LIMIT = 0.6f
    }
}

@Composable
internal fun rememberViewerDetailsState(): ViewerDetailsState {
    val scope = rememberCoroutineScope()
    return remember { ViewerDetailsState(scope) }
}

/**
 * Where the photo goes when the sheet is open: centred in the space left between the header and
 * the sheet, shrunk only if it would not fit there.
 */
internal data class ViewerDetailsPhotoTarget(val translationY: Float, val scale: Float)

internal fun viewerDetailsPhotoTarget(
    containerWidth: Float,
    containerHeight: Float,
    headerBottom: Float,
    sheetHeight: Float,
    item: MediaItem,
    marginPx: Float,
): ViewerDetailsPhotoTarget {
    val aspect = item.width
        ?.takeIf {
            it > 0
        }?.let { width -> item.height?.toFloat()?.div(width) } ?: 0.75f
    val photoHeight = minOf(containerHeight, containerWidth * aspect)
    val free = containerHeight - sheetHeight - headerBottom
    val scale = ((free - 2 * marginPx) / photoHeight).coerceIn(0.1f, 1f)
    val centre = headerBottom + free / 2f
    return ViewerDetailsPhotoTarget(centre - containerHeight / 2f, scale)
}

@Composable
internal fun ViewerDetailsSheet(
    item: MediaItem,
    state: ViewerDetailsState,
    flingThresholdPx: Float,
    modifier: Modifier = Modifier,
) {
    val drag = rememberDraggableState { state.dragBy(it) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.sheetHeightPx = it.height.toFloat() }
            .graphicsLayer {
                translationY = (1f - state.progress) * size.height
                alpha = if (state.progress > 0f) 1f else 0f
            }.background(SheetColor, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .draggable(
                state = drag,
                orientation = Orientation.Vertical,
                enabled = state.engaged,
                onDragStopped = { velocity -> state.settle(velocity, flingThresholdPx) },
            ).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp)
            .testTag(ViewerDetailsTestTag)
            // A closed sheet waits off screen to be measured; screen readers must not reach it.
            .then(if (state.engaged) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(
                        width = 32.dp,
                        height = 4.dp,
                    ).background(HandleColor, RoundedCornerShape(2.dp)),
            )
        }
        Text(
            text = stringResource(R.string.viewer_details),
            color = PrimaryText,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            // Memories and MediaStore both give a real instant, and Memories files it under the
            // local day. A local time on another day would contradict the header, so it is left
            // out: the local projection still files its days by UTC (#175).
            val time = item.takenAtEpochSeconds
                ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()) }
                ?.takeIf { it.toLocalDate() == item.day }
                ?.toLocalTime()
            DetailsRow(
                icon = rememberVectorPainter(Icons.Default.DateRange),
                title = listOfNotNull(
                    item.day.format(
                        DateTimeFormatter.ofPattern(
                            stringResource(R.string.viewer_details_date_pattern),
                        ),
                    ),
                    time?.format(
                        DateTimeFormatter.ofPattern(
                            stringResource(R.string.viewer_details_time_pattern),
                        ),
                    ),
                ).joinToString(", "),
                subtitle = item.day.format(
                    DateTimeFormatter.ofPattern(
                        stringResource(R.string.viewer_details_weekday_pattern),
                    ),
                ),
            )
            DetailsRow(
                icon = painterResource(R.drawable.ic_photos),
                title = item.displayName,
                subtitle = listOfNotNull(
                    resolution(item),
                    item.videoDurationSeconds?.let { "%d:%02d".format(it / 60, it % 60) },
                ).joinToString(" · ").ifEmpty { null },
            )
            if (item.hasLocalCopy) {
                DetailsRow(
                    icon = painterResource(R.drawable.ic_phone),
                    title = stringResource(R.string.viewer_details_on_phone),
                    subtitle = if (item.hasRemoteCopy) {
                        null
                    } else {
                        stringResource(
                            R.string.viewer_details_not_in_cloud,
                        )
                    },
                )
            }
            if (item.hasRemoteCopy) {
                DetailsRow(
                    icon = painterResource(R.drawable.ic_cloud),
                    title = stringResource(R.string.viewer_details_in_cloud),
                    subtitle = if (item.hasLocalCopy) {
                        null
                    } else {
                        stringResource(
                            R.string.viewer_details_cloud_only,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun resolution(item: MediaItem): String? {
    val width = item.width ?: return null
    val height = item.height ?: return null
    return stringResource(R.string.viewer_details_resolution, width, height)
}

@Composable
private fun DetailsRow(icon: Painter, title: String, subtitle: String?) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = SecondaryText, modifier = Modifier.size(20.dp))
        Column {
            Text(title, color = PrimaryText, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    color = SecondaryText,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

internal const val ViewerDetailsTestTag = "viewer_details"

// The viewer stays dark in either theme.
private val SheetColor = Color(0xFF211F26)
private val HandleColor = Color(0xFF938F99)
private val PrimaryText = Color(0xFFE6E0E9)
private val SecondaryText = Color(0xFFCAC4D0)
