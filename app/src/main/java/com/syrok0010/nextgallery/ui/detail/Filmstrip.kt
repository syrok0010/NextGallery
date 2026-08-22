package com.syrok0010.nextgallery.ui.detail

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.ui.common.MediaAssetImage
import com.syrok0010.nextgallery.ui.common.MediaImagePurpose
import kotlinx.coroutines.launch
import kotlin.math.abs

internal val FilmstripTileSize = 56.dp
internal val FilmstripTileSpacing = 6.dp
internal const val FilmstripTileActiveScale = 1.15f

internal const val FilmstripTestTag = "filmstrip"
internal fun filmstripTileTestTag(index: Int) = "filmstrip_tile_$index"

@Composable
internal fun Filmstrip(
    items: List<MediaItem>,
    currentPage: Int,
    credentials: AccountCredentials,
    onPageSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    lazyListState: LazyListState = rememberLazyListState(),
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
        val horizontalPadding = ((maxWidth - FilmstripTileSize) / 2f).coerceAtLeast(0.dp)

        LazyRow(
            state = lazyListState,
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                end = horizontalPadding,
                top = 8.dp,
                bottom = 8.dp,
            ),
            horizontalArrangement = Arrangement.spacedBy(FilmstripTileSpacing),
            verticalAlignment = Alignment.CenterVertically,
            userScrollEnabled = false, // We control scrolling via pointerInput drag & click
        ) {
            itemsIndexed(
                items = items,
                key = { _, item -> "filmstrip:${item.mediaId.value}" },
            ) { index, item ->
                val isSelected = index == currentPage
                val scale by animateFloatAsState(
                    targetValue = if (isSelected) FilmstripTileActiveScale else 1.0f,
                    label = "filmstrip_tile_scale",
                )

                Box(
                    modifier = Modifier
                        .size(FilmstripTileSize)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(RoundedCornerShape(8.dp))
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
                        credentials = credentials,
                        purpose = MediaImagePurpose.TimelineThumbnail,
                        contentDescription = item.displayName,
                        modifier = Modifier.size(FilmstripTileSize),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
    }
}
