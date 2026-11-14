package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlinx.coroutines.launch

internal data class ActiveViewerPageState(
    val hasHdr: Boolean = false,
    val canDragDown: Boolean = true,
)

@Composable
internal fun MediaDetailScreen(
    initialMediaId: MediaId,
    sequence: ViewerSequence,
    tileBoundsForMediaId: (mediaId: MediaId) -> Rect?,
    onBack: (MediaItem) -> Unit,
    onCurrentItemChange: (MediaItem) -> Unit,
    /** Nothing is left to show, e.g. the only object was deleted. */
    onEmpty: () -> Unit = {},
    /** Labeled actions for the current object; null hides the action row. */
    actions: (@Composable (MediaItem) -> Unit)? = null,
    status: @Composable (visible: Boolean) -> Unit = {},
) {
    val items = sequence.items
    val initialPage = sequence.pageIndex(initialMediaId) ?: 0
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    val coroutineScope = rememberCoroutineScope()
    // Right after the last page is removed the pager still points past the end for a frame.
    val currentPage = pagerState.currentPage.coerceAtMost(items.lastIndex)
    val currentItem = items.getOrNull(currentPage)
    val playback = rememberViewerPlaybackState(currentItem)
    val videoFullscreen = playback.current?.state?.isFullscreen == true
    // Entering and leaving fullscreen video shows the chrome again, in the same frame.
    var chromeVisible by rememberSaveable(videoFullscreen) { mutableStateOf(true) }
    var activePageState by remember(currentItem?.mediaId) {
        mutableStateOf(ActiveViewerPageState())
    }
    val motion = rememberViewerMotionState(
        initialMediaId = initialMediaId,
        currentMediaId = currentItem?.mediaId,
        tileBoundsForMediaId = tileBoundsForMediaId,
        onClose = { currentItem?.let(onBack) },
        backEnabled = !videoFullscreen,
    )
    ViewerWindowHdrEffect(enabled = activePageState.hasHdr)
    val details = rememberViewerDetailsState()
    // Registered after the viewer's own handler, so back closes the sheet before the viewer.
    BackHandler(enabled = details.isOpen) { details.close() }
    val density = LocalDensity.current
    val defaultHeaderBottom = with(density) { WindowInsets.statusBars.getTop(this) + 64.dp.toPx() }
    var headerBottom by remember { mutableFloatStateOf(defaultHeaderBottom) }
    val photoMarginPx = with(density) { 16.dp.toPx() }

    SideEffect(currentPage, items) {
        val item = items.getOrNull(currentPage) ?: return@SideEffect onEmpty()
        onCurrentItemChange(item)

    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = motion.backgroundAlpha,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .viewerDismissGestures(
                    motion,
                    currentItem?.mediaId,
                    activePageState.canDragDown && !videoFullscreen,
                    details,
                ).graphicsLayer {
                    val progress = details.progress
                    if (progress == 0f || currentItem == null) return@graphicsLayer
                    val target = viewerDetailsPhotoTarget(
                        containerWidth = size.width,
                        containerHeight = size.height,
                        headerBottom = headerBottom,
                        sheetHeight = details.sheetHeightPx,
                        item = currentItem,
                        marginPx = photoMarginPx,
                    )
                    translationY = progress * target.translationY
                    val scale = 1f + progress.coerceAtMost(1f) * (target.scale - 1f)
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
                key = sequence::pageKey,
            ) { page ->
                val item = items[page]
                MediaViewerPage(
                    item = item,
                    isCurrentPage = item.mediaId == currentItem?.mediaId,
                    surfaceTransform = if (item.mediaId == currentItem?.mediaId) {
                        motion.surfaceTransform(item.mediaId)
                    } else {
                        ViewerSurfaceTransform()
                    },
                    trackSurfaceBounds = item.mediaId == currentItem?.mediaId && motion.trackSurfaceBounds,
                    playbackController = playback.current?.takeIf { it.mediaId == item.mediaId },
                    onToggleChrome = {
                        if (details.engaged) details.close() else chromeVisible = !chromeVisible
                    },
                    onActivePageStateChange = { state ->
                        if (item.mediaId == currentItem?.mediaId) {
                            activePageState = state
                        }
                    },
                    onSurfaceBoundsChange = { bounds ->
                        if (item.mediaId == currentItem?.mediaId) {
                            motion.onSurfaceBoundsChange(bounds)
                        }
                    },
                )
            }
        }

        if (motion.chromeAllowed && currentItem != null && items.isNotEmpty()) {
            ViewerChrome(
                item = currentItem,
                visible = chromeVisible,
                playback = playback.current,
                actions = actions?.let { { it(currentItem) } },
                status = status,
                detailsProgress = { details.progress },
                onHeaderBottomChange = { headerBottom = it },
                onBack = { motion.close() },
                filmstrip = {
                    Filmstrip(
                        items  = items,
                        currentPage = pagerState.currentPage,
                        playback = playback.current ?: FilmstripPlayback.None,
                        onPageSelected = { page -> coroutineScope.launch { pagerState.scrollToPage(page) } },
                        // The action row under the filmstrip takes the navigation bar instead.
                        modifier = if (actions != null) {
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        } else {
                            Modifier
                        },
                    )
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (currentItem != null && !videoFullscreen) {
            ViewerDetailsSheet(
                item = currentItem,
                state = details,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
