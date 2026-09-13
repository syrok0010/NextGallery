package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
) {
    val items = sequence.items
    val initialPage = sequence.pageIndex(initialMediaId) ?: 0
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var videoFullscreen by remember(items.getOrNull(pagerState.currentPage)?.mediaId) { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val currentItem = items.getOrNull(pagerState.currentPage)
    var activePageState by remember(currentItem?.mediaId) {
        mutableStateOf(ActiveViewerPageState())
    }
    val motion = rememberViewerMotionState(
        initialMediaId = initialMediaId,
        currentMediaId = currentItem?.mediaId,
        tileBoundsForMediaId = tileBoundsForMediaId,
        onClose = { currentItem?.let(onBack) },
    )
    ViewerWindowHdrEffect(enabled = activePageState.hasHdr)

    SideEffect(pagerState.currentPage, items) {
        val item = items.getOrNull(pagerState.currentPage) ?: return@SideEffect
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
                .viewerDismissGestures(motion, currentItem?.mediaId, activePageState.canDragDown && !videoFullscreen),
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
                    controlsVisible = chromeVisible,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onActivePageStateChange = { state ->
                        if (item.mediaId == currentItem?.mediaId) {
                            activePageState = state
                        }
                    },
                    onFullscreenChanged = { isFullscreen ->
                        videoFullscreen = isFullscreen
                        chromeVisible = true
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
            AnimatedVisibility(
                visible = chromeVisible && !videoFullscreen,
                enter = fadeIn(animationSpec = tween(ViewerChromeFadeDurationMillis)),
                exit = fadeOut(animationSpec = tween(ViewerChromeFadeDurationMillis)),
                modifier = Modifier.fillMaxSize(),
            ) {
                ViewerChrome(
                    item = currentItem,
                    onBack = { motion.close() },
                    filmstrip = {
                        Filmstrip(
                            items = items,
                            currentPage = pagerState.currentPage,
                            onPageSelected = { page -> coroutineScope.launch { pagerState.scrollToPage(page) } },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private const val ViewerChromeFadeDurationMillis = 180
