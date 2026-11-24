package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.AnchoredDraggableDefaults
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import android.content.Intent
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.hasLocalCopy
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import java.time.Instant
import java.time.ZoneId
import com.syrok0010.nextgallery.core.ui.rememberDateFormatter
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

/**
 * The details sheet pulled up from under the photo. [progress] is 0 when closed and 1 when open.
 * The sheet drags itself; a swipe on the photo feeds the same state through [dragBy] and [settle].
 */
@Stable
internal class ViewerDetailsState(private val scope: CoroutineScope) {
    /** Offset is the sheet's translation: its height when closed, 0 when open. */
    val anchors = AnchoredDraggableState(initialValue = false)
    var sheetHeightPx by mutableFloatStateOf(0f)
        private set

    val progress: Float
        get() = if (sheetHeightPx > 0f && !anchors.offset.isNaN()) 1f - anchors.offset / sheetHeightPx else 0f

    /** Back and taps on the photo close a sheet that is open or on its way there. */
    val isOpen: Boolean get() = anchors.targetValue

    val engaged: Boolean get() = isOpen || progress > 0f

    fun onSheetHeight(heightPx: Float) {
        sheetHeightPx = heightPx
        anchors.updateAnchors(DraggableAnchors { false at heightPx; true at 0f })
    }

    fun dragBy(deltaPx: Float) {
        anchors.dispatchRawDelta(deltaPx)
    }

    /** [velocityPx] is the vertical drag velocity: negative flings up and opens. */
    fun settle(velocityPx: Float, flingThresholdPx: Float) {
        scope.launch {
            if (abs(velocityPx) > flingThresholdPx) anchors.animateTo(velocityPx < 0f, SheetSpring) else anchors.settle(SheetSpring)
        }
    }

    fun close() {
        scope.launch { anchors.animateTo(false, SheetSpring) }
    }
}

private val SheetSpring = spring<Float>(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)

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
    detailsLoader: ViewerDetailsLoader = koinInject(),
    modifier: Modifier = Modifier,
) {
    var details by remember(item.mediaId) { mutableStateOf<ViewerMediaDetails?>(null) }
    var detailsLoading by remember(item.mediaId) { mutableStateOf(false) }
    LaunchedEffect(item.mediaId, state.engaged) {
        if (!state.engaged) return@LaunchedEffect
        detailsLoading = true
        details = runCatching { detailsLoader.load(item) }.getOrNull()
        detailsLoading = false
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { state.onSheetHeight(it.height.toFloat()) }
            .graphicsLayer {
                translationY = (1f - state.progress) * size.height
                alpha = if (state.progress > 0f) 1f else 0f
            }.background(SheetColor, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .anchoredDraggable(
                state = state.anchors,
                orientation = Orientation.Vertical,
                enabled = state.engaged,
                flingBehavior = AnchoredDraggableDefaults.flingBehavior(state.anchors, animationSpec = SheetSpring),
            ).windowInsetsPadding(WindowInsets.navigationBars)
            .padding(bottom = 16.dp)
            .verticalScroll(rememberScrollState())
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
            // Memories supplies the photo's calendar day separately from its capture instant.
            // If the device uses a different timezone, omit a clock that contradicts that day.
            val time = item.takenAtEpochSeconds
                ?.let { Instant.ofEpochSecond(it).atZone(ZoneId.systemDefault()) }
                ?.takeIf { it.toLocalDate() == item.day }
                ?.toLocalTime()
            val date = rememberDateFormatter(R.string.viewer_details_date_pattern)
            val clock = rememberDateFormatter(R.string.viewer_details_time_pattern)
            val weekday = rememberDateFormatter(R.string.viewer_details_weekday_pattern)
            DetailsRow(
                icon = rememberVectorPainter(Icons.Default.DateRange),
                title = listOfNotNull(item.day.format(date), time?.format(clock)).joinToString(", "),
                subtitle = item.day.format(weekday),
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
            if (detailsLoading) {
                DetailsRow(
                    icon = rememberVectorPainter(Icons.Default.DateRange),
                    title = stringResource(R.string.viewer_details_loading),
                    subtitle = null,
                )
            }
            details?.let { RichDetails(it) }
        }
    }
}

@Composable
private fun RichDetails(details: ViewerMediaDetails) {
    val context = LocalContext.current
    details.cameraModel?.let {
        DetailsRow(
            icon = rememberVectorPainter(Icons.Default.DateRange),
            title = it,
            subtitle = details.lensModel,
        )
    }
    val exposure = listOfNotNull(
        details.exposureTimeSeconds?.let(::formatExposure),
        details.aperture?.let { "f/${formatDecimal(it)}" },
        details.iso?.let { "ISO $it" },
    ).filter(String::isNotBlank).joinToString(" · ").ifBlank { null }
    exposure?.let {
        DetailsRow(
            icon = rememberVectorPainter(Icons.Default.DateRange),
            title = stringResource(R.string.viewer_details_capture_settings),
            subtitle = it,
        )
    }
    details.fileSizeBytes?.let {
        DetailsRow(
            icon = painterResource(R.drawable.ic_albums),
            title = formatFileSize(it),
            subtitle = details.path,
        )
    } ?: details.path?.let {
        DetailsRow(
            icon = painterResource(R.drawable.ic_albums),
            title = stringResource(R.string.viewer_details_path),
            subtitle = it,
        )
    }
    if (details.albums.isNotEmpty()) {
        DetailsRow(
            icon = painterResource(R.drawable.ic_albums),
            title = stringResource(R.string.viewer_details_albums),
            subtitle = details.albums.joinToString(" · "),
        )
    }
    details.location?.let { point ->
        Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    rememberVectorPainter(Icons.Default.LocationOn),
                    contentDescription = null,
                    tint = SecondaryText,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.viewer_details_location),
                    color = PrimaryText,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Spacer(Modifier.height(6.dp))
            MiniMap(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clickable {
                        val uri = "geo:${point.latitude},${point.longitude}?q=${point.latitude},${point.longitude}".toUri()
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                    },
            )
            Text(
                "%.5f, %.5f".format(point.latitude, point.longitude),
                color = SecondaryText,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun MiniMap(modifier: Modifier = Modifier) {
    Canvas(modifier.background(Color(0xFF30363B), RoundedCornerShape(14.dp))) {
        val road = Color(0xFF555D61)
        repeat(5) { index ->
            val y = size.height * (index + 1) / 6f
            drawLine(road, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 2f)
        }
        repeat(4) { index ->
            val x = size.width * (index + 1) / 5f
            drawLine(road, androidx.compose.ui.geometry.Offset(x, 0f), androidx.compose.ui.geometry.Offset(x, size.height), 2f)
        }
        drawCircle(Color(0xFFE86A5B), radius = 10.dp.toPx(), center = center)
        drawCircle(Color.White, radius = 10.dp.toPx(), center = center, style = Stroke(width = 2.dp.toPx()))
    }
}

private fun formatExposure(seconds: Double): String = when {
    seconds <= 0.0 -> ""
    seconds >= 1.0 -> "%.1fs".format(seconds)
    else -> "1/%d s".format((1.0 / seconds).roundToInt())
}

private fun formatDecimal(value: Double): String = "%.1f".format(value).trimEnd('0').trimEnd('.')

private fun formatFileSize(bytes: Long): String = when {
    bytes < 1_024L -> "$bytes B"
    bytes < 1_024L * 1_024L -> "%.1f KB".format(bytes / 1_024.0)
    bytes < 1_024L * 1_024L * 1_024L -> "%.1f MB".format(bytes / (1_024.0 * 1_024.0))
    else -> "%.1f GB".format(bytes / (1_024.0 * 1_024.0 * 1_024.0))
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
