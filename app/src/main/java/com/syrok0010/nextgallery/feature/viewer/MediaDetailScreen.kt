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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
    detailsLoader: ViewerDetailsLoader? = null,
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
    // Hydration can shift indices before the pager remeasures. Read its displayed key
    // against the new sequence, rather than selecting a different photo at the old index.
    val displayedKey = pagerState.layoutInfo.visiblePagesInfo
        .firstOrNull { it.index == pagerState.currentPage }?.key as? String
    val currentPage = displayedKey?.let { sequence.pageIndex(MediaId(it)) }
        // Deleted pages have no surviving key; show the page that took their place.
        ?: pagerState.currentPage.coerceAtMost(items.lastIndex)
    val currentItem = items.getOrNull(currentPage)
    val details = rememberViewerDetailsState()
    var richDetails by remember(currentItem?.mediaId) { mutableStateOf<ViewerMediaDetails?>(null) }
    var detailsLoading by remember(currentItem?.mediaId) { mutableStateOf(false) }
    LaunchedEffect(currentItem?.mediaId, detailsLoader, details.engaged) {
        if (!details.engaged) return@LaunchedEffect
        detailsLoading = detailsLoader != null && currentItem != null
        richDetails = currentItem?.let { item ->
            detailsLoader?.let { loader -> runCatching { loader.load(item) }.getOrNull() }
        }
        detailsLoading = false
    }
    val playback = rememberViewerPlaybackState(currentItem)
    val latestSequence by rememberUpdatedState(sequence)
    val filmstrip = rememberViewerFilmstripState(items, currentItem?.mediaId, onSelected = { id ->
        coroutineScope.launch {
            latestSequence.pageIndex(id)?.let { pagerState.scrollToPage(it) }
        }
    })
    val frameCache = rememberVideoFilmstripCache()
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
    val chromeShown by remember(motion) { derivedStateOf { motion.chromeAlpha > 0f } }
    ViewerWindowHdrEffect(enabled = activePageState.hasHdr)
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

        if (chromeShown && currentItem != null && items.isNotEmpty()) {
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
                        frameCache = frameCache,
                        state = filmstrip,
                        playback = playback.current ?: FilmstripPlayback.None,
                        // The action row under the filmstrip takes the navigation bar instead.
                        modifier = if (actions != null) {
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        } else {
                            Modifier
                        },
                    )
                },
                // Part of the surface: shows over the darkened background, and leaves before it.
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = motion.chromeAlpha },
            )
        }
        if (currentItem != null && !videoFullscreen) {
            ViewerDetailsSheet(
                item = currentItem,
                state = details,
                details = richDetails,
                detailsLoading = detailsLoading,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
