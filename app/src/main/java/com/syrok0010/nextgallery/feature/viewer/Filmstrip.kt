package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.core.animateDpAsState
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
    lazyListState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage),
) {
    if (items.isEmpty()) return

    var expandedVideo by remember { mutableStateOf<MediaId?>(null) }
    LaunchedEffect(currentPage) {
        if (items.getOrNull(currentPage)?.mediaId != expandedVideo) expandedVideo = null
    }
    val expanded = items.getOrNull(currentPage)?.mediaId == expandedVideo
    val scrollState = rememberScrollableState { delta ->
        lazyListState.dispatchRawDelta(delta)
    }
    val latestPage by rememberUpdatedState(currentPage)
    val selectPage by rememberUpdatedState(onPageSelected)
    val isScrolling = scrollState.isScrollInProgress

    LaunchedEffect(scrollState, lazyListState, items.size) {
        snapshotFlow {
            if (scrollState.isScrollInProgress) {
                val layout = lazyListState.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                layout.visibleItemsInfo.minByOrNull {
                    abs(it.offset + it.size / 2 - center)
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
        if (!isScrolling) {
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
        val expandedWidth = minOf(320.dp, maxWidth * 0.75f)
        val selectedWidth = if (expanded) expandedWidth else FilmstripActiveTileWidth
        val horizontalPadding = ((maxWidth - selectedWidth) / 2f).coerceAtLeast(0.dp)

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
                val isExpanded = isSelected && item.mediaId == expandedVideo
                val tileWidth by animateDpAsState(
                    targetValue = if (isExpanded) expandedWidth else if (isSelected) FilmstripActiveTileWidth else FilmstripTileWidth,
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
                        .then(if (isExpanded) Modifier else Modifier.clickable {
                            if (item.isVideo && VideoSources.from(item.assetRef).primary.startsWith("content://")) {
                                expandedVideo = item.mediaId
                            }
                            onPageSelected(index)
                        }),
                ) {
                    if (isExpanded) VideoFilmstripCard(
                        item = item,
                        sourceUri = VideoSources.from(item.assetRef).primary,
                        onScrub = onVideoScrub,
                        onScrubFinished = onVideoScrubFinished,
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
}
