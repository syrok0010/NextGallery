package com.syrok0010.nextgallery.feature.viewer

import android.os.SystemClock
import android.graphics.Bitmap
import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

internal val FilmstripTileWidth = 34.dp
internal val FilmstripTileHeight = 60.dp
internal val FilmstripActiveTileWidth = 39.dp
internal val FilmstripActiveTileHeight = 69.dp
internal val FilmstripTileSpacing = 2.dp
internal val FilmstripRowHeight = 90.dp

internal const val FilmstripTestTag = "filmstrip"
internal fun filmstripTileTestTag(index: Int) = "filmstrip_tile_$index"

@Composable
internal fun Filmstrip(
    state: ViewerFilmstripState,
    modifier: Modifier = Modifier,
    playback: FilmstripPlayback = FilmstripPlayback.None,
    frameProvider: VideoFrameProvider? = null,
    frameCache: VideoFilmstripCache<Bitmap> = rememberVideoFilmstripCache(),
    /** Paces seeks while scrubbing; a test passes its own clock, which it advances by hand. */
    uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    val items = state.items
    if (items.isEmpty()) return
    val lazyListState = state.lazyListState

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.48f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(FilmstripTestTag)
            .scrollable(
                state = state.scrollState,
                orientation = Orientation.Horizontal,
                reverseDirection = true,
            ),
    ) {
        val horizontalPadding = ((maxWidth - FilmstripActiveTileWidth) / 2f).coerceAtLeast(0.dp)

        LazyRow(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .height(FilmstripRowHeight),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 16.dp,
                bottom = 4.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(FilmstripTileSpacing),
            verticalAlignment = Alignment.Bottom,
            userScrollEnabled = false,
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> filmstripKey(item.mediaId) },
            ) { index, item ->
                val isSelected = item.mediaId == state.currentMediaId
                val isExpanded = item.mediaId == state.expandedVideo
                val usesActiveSize = item.mediaId == state.activeSizeMediaId
                val compactWidth = if (usesActiveSize) FilmstripActiveTileWidth else FilmstripTileWidth
                val height = if (isSelected) FilmstripActiveTileHeight else FilmstripTileHeight
                val itemModifier = Modifier.testTag(filmstripTileTestTag(index))
                if (item.isVideo) {
                    VideoFilmstripItem(
                        item = item,
                        onClick = { state.click(item.mediaId) },
                        placement = VideoFilmstripPlacement(
                            expanded = isExpanded,
                            selected = isSelected,
                            activeWidth = usesActiveSize,
                            fraction = state.fraction(item.mediaId),
                            scrolling = state.isScrolling,
                        ),
                        playback = playback,
                        onSeekFraction = { state.movePlayhead(item.mediaId, it) },
                        frameProvider = frameProvider,
                        frameCache = frameCache,
                        uptimeMillis = uptimeMillis,
                        modifier = itemModifier,
                    )
                } else {
                    PhotoFilmstripItem(
                        item = item,
                        width = compactWidth,
                        height = height,
                        onClick = { state.click(item.mediaId) },
                        modifier = itemModifier,
                    )
                }
            }
        }
        if (state.expanded) {
            Canvas(Modifier.fillMaxWidth().height(FilmstripRowHeight).testTag("video_filmstrip_playhead")) {
                drawLine(Color.White, Offset(size.width / 2, 16.dp.toPx()),
                    Offset(size.width / 2, size.height - 4.dp.toPx()), 2.dp.toPx())
            }

        }
    }
}

@Composable
internal fun rememberVideoFilmstripCache(): VideoFilmstripCache<Bitmap> {
    val cache = remember { VideoFilmstripCache<Bitmap>(8L * 1024 * 1024) { it.allocationByteCount } }
    DisposableEffect(cache) { onDispose { cache.clear() } }
    return cache
}
