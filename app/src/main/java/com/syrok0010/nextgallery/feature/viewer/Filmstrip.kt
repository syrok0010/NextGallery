package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.res.stringResource
import com.syrok0010.nextgallery.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import kotlin.math.abs

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
    items: List<MediaItem>,
    currentPage: Int,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onVideoScrub: (Long, Boolean) -> Unit = { _, _ -> },
    onVideoScrubFinished: () -> Unit = {},
    frameProvider: VideoFrameProvider? = null,
    activeVideoSource: String? = null,
    lazyListState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage),
) {
    if (items.isEmpty()) return

    var expandedVideo by remember { mutableStateOf<MediaId?>(null) }
    var expansionSelection by remember { mutableStateOf<MediaId?>(null) }
    val scope = rememberCoroutineScope()
    val expanded = expandedVideo != null
    val scrollState = rememberScrollableState { delta ->
        lazyListState.dispatchRawDelta(delta)
    }
    val latestPage by rememberUpdatedState(currentPage)
    val selectPage by rememberUpdatedState(onPageSelected)
    val latestExpanded by rememberUpdatedState(expandedVideo)
    val isScrolling = scrollState.isScrollInProgress

    LaunchedEffect(scrollState, lazyListState, items.size) {
        snapshotFlow {
            if (scrollState.isScrollInProgress) {
                val layout = lazyListState.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull {
                    if (items.getOrNull(it.index)?.mediaId == latestExpanded) {
                        when {
                            center < it.offset -> it.offset - center
                            center > it.offset + it.size -> center - it.offset - it.size
                            else -> 0
                        }
                    } else abs(it.offset + it.size / 2 - center)
                }?.index
            } else {
                null
            }
        }.collect { page ->
            if (page != null && page in items.indices && page != latestPage) {
                selectPage(page)
            }
        }
    }

    LaunchedEffect(currentPage, isScrolling, items.size, expanded) {
        if (!isScrolling && !expanded) {
            lazyListState.animateScrollToItem(currentPage.coerceIn(items.indices))
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.48f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(FilmstripTestTag)
            .scrollable(
                state = scrollState,
                orientation = Orientation.Horizontal,
                reverseDirection = true,
            ),
    ) {
        val expandedWidth = maxWidth * VideoFilmstripScreenWidths
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
                key = { _, item -> "filmstrip:${item.mediaId.value}" },
            ) { index, item ->
                val isSelected = index == currentPage
                val isExpanded = item.mediaId == expandedVideo
                val usesActiveSize = item.mediaId == (expansionSelection ?: items.getOrNull(currentPage)?.mediaId)
                val tileWidth by animateDpAsState(
                    targetValue = if (isExpanded) expandedWidth else if (usesActiveSize) FilmstripActiveTileWidth else FilmstripTileWidth,
                    animationSpec = tween(250),
                    label = "filmstrip_tile_width",
                )
                val tileHeight by animateDpAsState(
                    targetValue = if (isSelected) FilmstripActiveTileHeight else FilmstripTileHeight,
                    label = "filmstrip_tile_height",
                )

                Box(
                    modifier = Modifier
                        .size(width = tileWidth, height = tileHeight)
                        .clip(RoundedCornerShape(4.dp))
                        .testTag(filmstripTileTestTag(index))
                        .clickable(onClickLabel = if (isExpanded) stringResource(R.string.video_filmstrip_collapse) else null) {
                            if (isExpanded) {
                                onVideoScrubFinished()
                                expandedVideo = null
                                expansionSelection = null
                            } else if (item.isVideo) {
                                if (expandedVideo == null) expansionSelection = items.getOrNull(currentPage)?.mediaId
                                expandedVideo = item.mediaId
                            }
                            onPageSelected(index)
                        },
                ) {
                    Crossfade(targetState = isExpanded, animationSpec = tween(250), label = "video_card_expansion") { showFrames ->
                        if (showFrames) VideoFilmstripCard(
                            item = item,
                            sourceUri = activeVideoSource.takeIf { isSelected } ?: VideoSources.from(item.assetRef).primary,
                            fallbackUri = VideoSources.from(item.assetRef).fallback,
                            fraction = lazyListState.layoutInfo.let { layout ->
                                val info = layout.visibleItemsInfo.firstOrNull { it.index == index }
                                if (info == null) 0f else ((layout.viewportStartOffset + layout.viewportEndOffset) / 2f - info.offset)
                                    .div(info.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                            },
                            isScrolling = isScrolling && isSelected && isExpanded,
                            onSeekFraction = { fraction ->
                                val info = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
                                if (info != null) scope.launch {
                                    val layout = lazyListState.layoutInfo
                                    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
                                    lazyListState.scrollToItem(index, (info.size * fraction - center).toInt())
                                }
                            },
                            onScrub = { position, finished -> if (isExpanded && isSelected) onVideoScrub(position, finished) },
                            onScrubFinished = { if (isExpanded && isSelected) onVideoScrubFinished() },
                            frameProvider = frameProvider,
                            modifier = Modifier.size(width = tileWidth, height = tileHeight),
                        ) else MediaAssetImage(
                            item = item,
                            purpose = MediaImagePurpose.TimelineThumbnail,
                            contentDescription = item.displayName,
                            modifier = Modifier.size(width = tileWidth, height = tileHeight),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
            }
        }
        if (expanded) {
            Canvas(Modifier.fillMaxWidth().height(FilmstripRowHeight).testTag("video_filmstrip_playhead")) {
                drawLine(Color.White, Offset(size.width / 2, 16.dp.toPx()),
                    Offset(size.width / 2, size.height - 4.dp.toPx()), 2.dp.toPx())
            }

        }
    }
}
