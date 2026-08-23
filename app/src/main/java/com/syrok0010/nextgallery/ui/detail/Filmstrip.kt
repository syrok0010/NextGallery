package com.syrok0010.nextgallery.ui.detail

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.common.MediaAssetImage
import com.syrok0010.nextgallery.ui.common.MediaImagePurpose
import kotlinx.coroutines.launch
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
    lazyListState: LazyListState = rememberLazyListState(initialFirstVisibleItemIndex = currentPage),
) {
    if (items.isEmpty()) return

    val coroutineScope = rememberCoroutineScope()
    var isDragging by remember { mutableStateOf(false) }
    var currentScrubbedPage by remember { mutableIntStateOf(currentPage) }

    LaunchedEffect(currentPage, isDragging) {
        if (!isDragging && items.isNotEmpty()) {
            val safePage = currentPage.coerceIn(0, items.size - 1)
            lazyListState.animateScrollToItem(safePage)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.48f))
            .windowInsetsPadding(WindowInsets.navigationBars)
            .testTag(FilmstripTestTag)
            .pointerInput(items.size) {
                detectDragGestures(
                    onDragStart = {
                        isDragging = true
                        currentScrubbedPage = currentPage
                    },
                    onDragCancel = {
                        isDragging = false
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(currentPage.coerceIn(0, items.size - 1))
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        val targetPage = currentScrubbedPage.coerceIn(0, items.size - 1)
                        onPageSelected(targetPage)
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(targetPage)
                        }
                    },
                ) { change, dragAmount ->
                    change.consume()
                    lazyListState.dispatchRawDelta(-dragAmount.x)

                    val layoutInfo = lazyListState.layoutInfo
                    val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
                    val centerItem = layoutInfo.visibleItemsInfo.minByOrNull { itemInfo ->
                        val itemCenter = itemInfo.offset + itemInfo.size / 2
                        abs(itemCenter - viewportCenter)
                    }
                    if (centerItem != null && centerItem.index in items.indices) {
                        currentScrubbedPage = centerItem.index
                        if (centerItem.index != currentPage) {
                            onPageSelected(centerItem.index)
                        }
                    }
                }
            },
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
            userScrollEnabled = false, // We control scrolling via pointerInput drag & click
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "filmstrip:${item.mediaId.value}" },
            ) { index, item ->
                val isSelected = index == currentPage
                val tileWidth by animateDpAsState(
                    targetValue = if (isSelected) FilmstripActiveTileWidth else FilmstripTileWidth,
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
                        .clickable {
                            onPageSelected(index)
                            coroutineScope.launch {
                                lazyListState.animateScrollToItem(index)
                            }
                        },
                ) {
                    MediaAssetImage(
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
