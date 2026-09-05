package com.syrok0010.nextgallery.ui.detail

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.domain.media.MediaId
import kotlinx.coroutines.launch

@Composable
internal fun MediaDetailScreen(
    initialMediaId: MediaId,
    sequence: ViewerSequence,
    tileBoundsForMediaId: (mediaId: MediaId) -> Rect?,
    onBack: (MediaItem) -> Unit,
    onCurrentItemChange: (MediaItem) -> Unit,
    onVisibleTimelineRange: (firstVisibleIndex: Int, lastVisibleIndex: Int) -> Unit,
) {
    val items = sequence.items
    val initialPage = sequence.pageIndex(initialMediaId) ?: 0
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    val coroutineScope = rememberCoroutineScope()
    val pageZoomedOutByMediaId = remember { mutableStateMapOf<MediaId, Boolean>() }
    val hdrByMediaId = remember { mutableStateMapOf<MediaId, Boolean>() }
    val currentItem = items.getOrNull(pagerState.currentPage)
    val currentPageHasHdr = currentItem?.let { hdrByMediaId[it.mediaId] == true } == true
    val currentPageCanDragDown = currentItem
        ?.let { pageZoomedOutByMediaId[it.mediaId] }
        ?: true
    val motion = rememberViewerMotionState(
        initialMediaId = initialMediaId,
        currentMediaId = currentItem?.mediaId,
        tileBoundsForMediaId = tileBoundsForMediaId,
        onClose = { currentItem?.let(onBack) },
    )
    ViewerWindowHdrEffect(enabled = currentPageHasHdr)

    LaunchedEffect(pagerState.currentPage, items) {
        val item = items.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        onCurrentItemChange(item)
        val slotIndex = sequence.timelineSlotIndex(item.mediaId) ?: return@LaunchedEffect
        onVisibleTimelineRange(
            (slotIndex - ViewerSequencePrefetchSlots).coerceAtLeast(0),
            slotIndex + ViewerSequencePrefetchSlots,
        )
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
                .viewerDismissGestures(motion, currentItem?.mediaId, currentPageCanDragDown),
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
                    isCurrentPage = page == pagerState.currentPage,
                    surfaceTransform = if (page == pagerState.currentPage) {
                        motion.surfaceTransform(item.mediaId)
                    } else {
                        ViewerSurfaceTransform()
                    },
                    trackSurfaceBounds = page == pagerState.currentPage && motion.trackSurfaceBounds,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onHdrChange = { hasHdr ->
                        hdrByMediaId[item.mediaId] = hasHdr
                    },
                    onZoomedOutChange = { isZoomedOut ->
                        pageZoomedOutByMediaId[item.mediaId] = isZoomedOut
                    },
                    onSurfaceBoundsChange = { bounds ->
                        if (page == pagerState.currentPage) {
                            motion.onSurfaceBoundsChange(bounds)
                        }
                    },
                )
            }
        }

        if (motion.chromeAllowed && currentItem != null && items.isNotEmpty()) {
            AnimatedVisibility(
                visible = chromeVisible,
                enter = fadeIn(animationSpec = tween(ViewerChromeFadeDurationMillis)),
                exit = fadeOut(animationSpec = tween(ViewerChromeFadeDurationMillis)),
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

private const val ViewerSequencePrefetchSlots = 80
private const val ViewerChromeFadeDurationMillis = 180
