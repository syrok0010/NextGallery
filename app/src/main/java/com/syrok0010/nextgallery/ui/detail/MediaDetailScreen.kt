package com.syrok0010.nextgallery.ui.detail

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.Image
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesAssetUrlFactory
import com.syrok0010.nextgallery.data.thumbnail.thumbnailRequest
import com.syrok0010.nextgallery.ui.common.AuthenticatedImage
import com.syrok0010.nextgallery.ui.common.ThumbnailImage
import com.syrok0010.nextgallery.ui.common.authenticatedImageRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import java.time.format.DateTimeFormatter

@Composable
internal fun MediaDetailScreen(
    initialFileId: Long,
    items: List<MediaItem>,
    slotIndexByFileId: Map<Long, Int>,
    tileBoundsForFileId: (fileId: Long) -> Rect?,
    credentials: AccountCredentials,
    onBack: (MediaItem) -> Unit,
    onCurrentItemChange: (MediaItem) -> Unit,
    onVisibleTimelineRange: (firstVisibleIndex: Int, lastVisibleIndex: Int) -> Unit,
) {
    val initialPage = remember(initialFileId, items) {
        items.indexOfFirst { it.fileId == initialFileId }.coerceAtLeast(0)
    }
    val pagerState = rememberPagerState(initialPage = initialPage) { items.size }
    val openingFileId = remember { initialFileId }
    var chromeVisible by remember { mutableStateOf(true) }
    var predictiveBackProgress by remember { mutableFloatStateOf(0f) }
    var currentSurfaceBounds by remember { mutableStateOf<Rect?>(null) }
    var enterPending by remember { mutableStateOf(true) }
    var enterTarget by remember { mutableStateOf<ViewerBoundsTransform?>(null) }
    var settleTarget by remember { mutableStateOf<ViewerBoundsTransform?>(null) }
    val dragOffset = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    val enterProgress = remember { Animatable(0f) }
    val settleProgress = remember { Animatable(0f) }
    val backgroundAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val dismissThresholdPx = with(LocalDensity.current) { ViewerDismissThreshold.toPx() }
    val pageZoomedOutByFileId = remember { mutableStateMapOf<Long, Boolean>() }
    val hdrByFileId = remember { mutableStateMapOf<Long, Boolean>() }
    val currentItem = items.getOrNull(pagerState.currentPage)
    val activity = LocalContext.current.findActivity()
    val currentPageHasHdr = currentItem?.let { hdrByFileId[it.fileId] == true } == true
    val currentPageCanDragDown = currentItem
        ?.let { pageZoomedOutByFileId[it.fileId] }
        ?: true
    val dismissInProgress = dragOffset.value != Offset.Zero ||
        predictiveBackProgress > 0f ||
        settleTarget != null
    val enterInProgress = enterPending || enterTarget != null
    val closeViewer: (MediaItem, Boolean) -> Unit = { item, animateToTile ->
        coroutineScope.launch {
            val target = if (animateToTile) {
                currentSurfaceBounds?.settleTarget(
                    tileBounds = tileBoundsForFileId(item.fileId),
                    dragOffset = dragOffset.value,
                    predictiveBackProgress = predictiveBackProgress,
                )
            } else {
                null
            }

            if (target != null) {
                settleProgress.snapTo(0f)
                settleTarget = target
                launch {
                    backgroundAlpha.animateTo(
                        targetValue = 0f,
                        animationSpec = tween(durationMillis = ViewerBackgroundExitDurationMillis),
                    )
                }
                settleProgress.animateTo(
                    targetValue = 1f,
                    animationSpec = tween(durationMillis = ViewerSettleDurationMillis),
                )
            } else {
                backgroundAlpha.snapTo(0f)
            }
            onBack(item)
        }
    }

    LaunchedEffect(Unit) {
        backgroundAlpha.snapTo(0f)
        backgroundAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = ViewerBackgroundEnterDurationMillis,
                delayMillis = ViewerBackgroundEnterDelayMillis,
            ),
        )
    }

    LaunchedEffect(
        currentItem?.fileId,
        currentSurfaceBounds,
    ) {
        if (!enterPending) {
            return@LaunchedEffect
        }

        val item = currentItem ?: return@LaunchedEffect
        if (item.fileId != openingFileId) {
            enterProgress.snapTo(1f)
            enterPending = false
            return@LaunchedEffect
        }

        val surfaceBounds = currentSurfaceBounds ?: return@LaunchedEffect
        val target = surfaceBounds.enterTarget(tileBoundsForFileId(item.fileId))
        if (target == null) {
            enterProgress.snapTo(1f)
            enterPending = false
            return@LaunchedEffect
        }

        enterProgress.snapTo(0f)
        enterTarget = target
        enterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = ViewerEnterDurationMillis),
        )
        enterTarget = null
        enterPending = false
    }

    LaunchedEffect(activity, currentPageHasHdr) {
        activity?.window?.colorMode = if (currentPageHasHdr) {
            ActivityInfo.COLOR_MODE_HDR
        } else {
            ActivityInfo.COLOR_MODE_DEFAULT
        }
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.window?.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
        }
    }

    PredictiveBackHandler(enabled = currentItem != null) { progress ->
        val item = currentItem ?: return@PredictiveBackHandler
        try {
            progress.collect { backEvent ->
                predictiveBackProgress = backEvent.progress
            }
            closeViewer(item, true)
        } catch (error: CancellationException) {
            predictiveBackProgress = 0f
            throw error
        }
    }

    LaunchedEffect(pagerState.currentPage, items) {
        val item = items.getOrNull(pagerState.currentPage) ?: return@LaunchedEffect
        onCurrentItemChange(item)
        val slotIndex = slotIndexByFileId[item.fileId] ?: return@LaunchedEffect
        onVisibleTimelineRange(
            (slotIndex - ViewerTimelinePrefetchSlots).coerceAtLeast(0),
            slotIndex + ViewerTimelinePrefetchSlots,
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Color.Black.copy(
                    alpha = backgroundAlpha.value *
                        viewerBackgroundAlpha(dragOffset.value, predictiveBackProgress),
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(currentItem?.fileId, currentPageCanDragDown) {
                    detectDragGestures(
                        onDragCancel = {
                            predictiveBackProgress = 0f
                            coroutineScope.launch {
                                dragOffset.animateTo(Offset.Zero)
                            }
                        },
                        onDragEnd = {
                            if (currentItem != null && dragOffset.value.y > dismissThresholdPx) {
                                closeViewer(currentItem, true)
                            } else {
                                coroutineScope.launch {
                                    dragOffset.animateTo(Offset.Zero)
                                }
                            }
                        },
                    ) { change, dragAmount ->
                        if (!currentPageCanDragDown) {
                            return@detectDragGestures
                        }
                        val nextOffset = dragOffset.value + dragAmount
                        if (nextOffset.y >= 0f && kotlin.math.abs(nextOffset.y) >= kotlin.math.abs(nextOffset.x)) {
                            change.consume()
                            coroutineScope.launch {
                                dragOffset.snapTo(nextOffset)
                            }
                        }
                    }
                },
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                beyondViewportPageCount = 1,
            ) { page ->
                val item = items[page]
                MediaViewerPage(
                    item = item,
                    isCurrentPage = page == pagerState.currentPage,
                    dragOffset = dragOffset.value,
                    predictiveBackProgress = predictiveBackProgress,
                    enterPending = enterPending && item.fileId == openingFileId,
                    enterTarget = enterTarget,
                    enterProgress = enterProgress.value,
                    settleTarget = settleTarget,
                    settleProgress = settleProgress.value,
                    predictiveTarget = if (page == pagerState.currentPage) {
                        currentSurfaceBounds?.settleTarget(
                            tileBounds = tileBoundsForFileId(item.fileId),
                            dragOffset = Offset.Zero,
                            predictiveBackProgress = 0f,
                        )
                    } else {
                        null
                    },
                    credentials = credentials,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onHdrChange = { hasHdr ->
                        hdrByFileId[item.fileId] = hasHdr
                    },
                    onZoomedOutChange = { isZoomedOut ->
                        pageZoomedOutByFileId[item.fileId] = isZoomedOut
                    },
                    onSurfaceBoundsChange = { bounds ->
                        if (page == pagerState.currentPage) {
                            currentSurfaceBounds = bounds
                        }
                    },
                )
            }
        }

        if (chromeVisible && !enterInProgress && !dismissInProgress && currentItem != null) {
            ViewerChrome(
                item = currentItem,
                page = pagerState.currentPage,
                pageCount = items.size,
                onBack = { closeViewer(currentItem, true) },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
    }
}

@Composable
private fun MediaViewerPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    dragOffset: Offset,
    predictiveBackProgress: Float,
    enterPending: Boolean,
    enterTarget: ViewerBoundsTransform?,
    enterProgress: Float,
    settleTarget: ViewerBoundsTransform?,
    settleProgress: Float,
    predictiveTarget: ViewerBoundsTransform?,
    credentials: AccountCredentials,
    onToggleChrome: () -> Unit,
    onHdrChange: (Boolean) -> Unit,
    onZoomedOutChange: (Boolean) -> Unit,
    onSurfaceBoundsChange: (Rect?) -> Unit,
) {
    val imageUrls = remember(item.assetRef, credentials.serverUrl) {
        MemoriesAssetUrlFactory.urlsFor(
            assetRef = item.assetRef,
            serverUrl = credentials.serverUrl,
        )
    }
    val thumbnail = remember(credentials, item.fileId, item.etag) {
        thumbnailRequest(
            credentials = credentials,
            fileId = item.fileId,
            etag = item.etag,
        )
    }

    val shouldUpdateSurfaceBounds = isCurrentPage &&
        dragOffset == Offset.Zero &&
        predictiveBackProgress == 0f &&
        enterTarget == null &&
        settleTarget == null
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        val contentSurfaceModifier = Modifier
            .viewerSurfaceSize(
                item = item,
                viewportWidth = maxWidth,
                viewportHeight = maxHeight,
            )
        val pageTransformModifier = if (isCurrentPage) {
            Modifier.viewerSurfaceTransform(
                dragOffset = dragOffset,
                predictiveBackProgress = predictiveBackProgress,
                enterPending = enterPending,
                enterTarget = enterTarget,
                enterProgress = enterProgress,
                settleTarget = settleTarget,
                settleProgress = settleProgress,
                predictiveTarget = predictiveTarget,
            )
        } else {
            Modifier
        }

        DisposableEffect(item.fileId, isCurrentPage) {
            onDispose {
                if (isCurrentPage) {
                    onSurfaceBoundsChange(null)
                }
            }
        }

        if (item.isVideo) {
            Box(
                modifier = contentSurfaceModifier
                    .then(pageTransformModifier)
                    .onGloballyPositioned { coordinates ->
                        if (shouldUpdateSurfaceBounds) {
                            onSurfaceBoundsChange(coordinates.boundsInRoot())
                        }
                    },
            ) {
                ThumbnailImage(
                    request = thumbnail,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                AuthenticatedImage(
                    url = imageUrls.detailPreviewUrl,
                    credentials = credentials,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

                LaunchedEffect(item.fileId) {
                    onHdrChange(false)
                    onZoomedOutChange(true)
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(onClick = onToggleChrome),
                )
                VideoBadge(modifier = Modifier.align(Alignment.Center))
            }
        } else {
            val context = LocalContext.current
            val zoomableState = rememberZoomableState()
            val zoomableImageState = rememberZoomableImageState(zoomableState)
            val originalRequest = remember(context, imageUrls.originalUrl, credentials) {
                authenticatedImageRequest(
                    context = context,
                    url = imageUrls.originalUrl,
                    credentials = credentials,
                )
                    .newBuilder(context)
                    .listener(
                        onSuccess = { _, result ->
                            onHdrChange(result.image.hasGainmapCompat())
                        },
                        onError = { _, _ ->
                            onHdrChange(false)
                        },
                    )
                    .build()
            }

            LaunchedEffect(item.fileId, zoomableState) {
                snapshotFlow { zoomableState.zoomFraction ?: 0f }
                    .collect { zoomFraction ->
                        onZoomedOutChange(zoomFraction <= 0.01f)
                    }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(pageTransformModifier),
            ) {
                Box(
                    modifier = contentSurfaceModifier
                        .align(Alignment.Center)
                        .onGloballyPositioned { coordinates ->
                            if (shouldUpdateSurfaceBounds) {
                                onSurfaceBoundsChange(coordinates.boundsInRoot())
                            }
                        },
                ) {
                    ThumbnailImage(
                        request = thumbnail,
                        contentDescription = item.displayName,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                    )
                }

                ZoomableAsyncImage(
                    model = originalRequest,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    state = zoomableImageState,
                    contentScale = ContentScale.Fit,
                    onClick = { onToggleChrome() },
                )
            }
        }
    }
}

@Composable
private fun ViewerChrome(
    item: MediaItem,
    page: Int,
    pageCount: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.48f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = Color.White,
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = item.day.format(DateTimeFormatter.ISO_LOCAL_DATE),
                    color = Color.White.copy(alpha = 0.72f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text(
                text = stringResource(R.string.viewer_position, page + 1, pageCount),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun VideoBadge(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.media_video_badge),
        modifier = modifier
            .background(
                color = Color.Black.copy(alpha = 0.64f),
                shape = MaterialTheme.shapes.small,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
    )
}

private fun Modifier.viewerSurfaceTransform(
    dragOffset: Offset,
    predictiveBackProgress: Float,
    enterPending: Boolean,
    enterTarget: ViewerBoundsTransform?,
    enterProgress: Float,
    settleTarget: ViewerBoundsTransform?,
    settleProgress: Float,
    predictiveTarget: ViewerBoundsTransform?,
): Modifier {
    val dragScale = viewerDragScale(dragOffset)
    val predictiveProgress = predictiveBackProgress.coerceIn(0f, 1f)
    val clipShape = when {
        settleTarget != null -> ViewerTransitionClipShape(
            transform = settleTarget,
            progress = settleProgress,
            opening = false,
        )
        predictiveProgress > 0f && predictiveTarget != null -> ViewerTransitionClipShape(
            transform = predictiveTarget,
            progress = predictiveProgress,
            opening = false,
            startScaleOverride = 1f,
        )
        enterTarget != null -> ViewerTransitionClipShape(
            transform = enterTarget,
            progress = enterProgress,
            opening = true,
        )
        else -> null
    }

    return graphicsLayer {
        if (clipShape != null) {
            shape = clipShape
            clip = true
        }
        if (settleTarget != null) {
            val progress = settleProgress.coerceIn(0f, 1f)
            val offset = lerpOffset(settleTarget.startOffset, settleTarget.targetOffset, progress)
            translationX = offset.x
            translationY = offset.y
            val scale = lerpFloat(settleTarget.startScale, settleTarget.targetScale, progress)
            scaleX = scale
            scaleY = scale
        } else if (predictiveProgress > 0f && predictiveTarget != null) {
            val offset = lerpOffset(Offset.Zero, predictiveTarget.targetOffset, predictiveProgress)
            translationX = offset.x
            translationY = offset.y
            val scale = lerpFloat(1f, predictiveTarget.targetScale, predictiveProgress)
            scaleX = scale
            scaleY = scale
        } else if (enterTarget != null) {
            val progress = enterProgress.coerceIn(0f, 1f)
            val offset = lerpOffset(enterTarget.startOffset, enterTarget.targetOffset, progress)
            translationX = offset.x
            translationY = offset.y
            val scale = lerpFloat(enterTarget.startScale, enterTarget.targetScale, progress)
            scaleX = scale
            scaleY = scale
        } else if (enterPending) {
            alpha = 0f
        } else {
            translationX = dragOffset.x
            translationY = dragOffset.y
            scaleX = dragScale
            scaleY = dragScale
        }
    }
}

internal fun animatedLocalClipSize(
    layerWidth: Float,
    layerHeight: Float,
    startScale: Float,
    targetScale: Float,
    targetClipWidth: Float,
    targetClipHeight: Float,
    progress: Float,
    opening: Boolean,
): Offset {
    val fraction = progress.coerceIn(0f, 1f)
    val currentScale = lerpFloat(startScale, targetScale, fraction)
        .coerceAtLeast(0.01f)
    val targetScreenWidth = targetClipWidth * startScale
    val targetScreenHeight = targetClipHeight * startScale

    val screenWidth = if (opening) {
        lerpFloat(targetScreenWidth, layerWidth * targetScale, fraction)
    } else {
        lerpFloat(layerWidth * startScale, targetClipWidth * targetScale, fraction)
    }
    val screenHeight = if (opening) {
        lerpFloat(targetScreenHeight, layerHeight * targetScale, fraction)
    } else {
        lerpFloat(layerHeight * startScale, targetClipHeight * targetScale, fraction)
    }

    return Offset(screenWidth / currentScale, screenHeight / currentScale)
}

private data class ViewerTransitionClipShape(
    val transform: ViewerBoundsTransform,
    val progress: Float,
    val opening: Boolean,
    val startScaleOverride: Float? = null,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val clipSize = animatedLocalClipSize(
            layerWidth = size.width,
            layerHeight = size.height,
            startScale = startScaleOverride ?: transform.startScale,
            targetScale = transform.targetScale,
            targetClipWidth = transform.targetClipWidth,
            targetClipHeight = transform.targetClipHeight,
            progress = progress,
            opening = opening,
        )
        val clipWidth = clipSize.x.coerceIn(0f, size.width)
        val clipHeight = clipSize.y.coerceIn(0f, size.height)
        val left = (size.width - clipWidth) / 2f
        val top = (size.height - clipHeight) / 2f
        return Outline.Rectangle(
            Rect(
                left = left,
                top = top,
                right = left + clipWidth,
                bottom = top + clipHeight,
            ),
        )
    }
}

internal data class ViewerBoundsTransform(
    val startOffset: Offset,
    val targetOffset: Offset,
    val startScale: Float,
    val targetScale: Float,
    val targetClipWidth: Float,
    val targetClipHeight: Float,
)

private fun Rect.settleTarget(
    tileBounds: Rect?,
    dragOffset: Offset,
    predictiveBackProgress: Float,
): ViewerBoundsTransform? {
    if (tileBounds == null || width <= 0f || height <= 0f) {
        return null
    }

    val targetOffset = tileBounds.center - center
    val targetSide = minOf(tileBounds.width, tileBounds.height)
    val targetScale = viewerCropScale(targetSide).coerceIn(0.01f, 1f)
    val predictiveProgress = predictiveBackProgress.coerceIn(0f, 1f)
    val dragScale = viewerDragScale(dragOffset)
    val startOffset = if (predictiveProgress > 0f) {
        lerpOffset(Offset.Zero, targetOffset, predictiveProgress)
    } else {
        dragOffset
    }
    val startScale = if (predictiveProgress > 0f) {
        lerpFloat(1f, targetScale, predictiveProgress)
    } else {
        dragScale
    }

    return ViewerBoundsTransform(
        startOffset = startOffset,
        targetOffset = targetOffset,
        startScale = startScale,
        targetScale = targetScale,
        targetClipWidth = targetSide / targetScale,
        targetClipHeight = targetSide / targetScale,
    )
}

private fun Rect.enterTarget(tileBounds: Rect?): ViewerBoundsTransform? {
    if (tileBounds == null || width <= 0f || height <= 0f) {
        return null
    }

    val targetSide = minOf(tileBounds.width, tileBounds.height)
    val startScale = viewerCropScale(targetSide).coerceIn(0.01f, 1f)
    return ViewerBoundsTransform(
        startOffset = tileBounds.center - center,
        targetOffset = Offset.Zero,
        startScale = startScale,
        targetScale = 1f,
        targetClipWidth = targetSide / startScale,
        targetClipHeight = targetSide / startScale,
    )
}

internal fun Rect.viewerCropScale(targetSide: Float): Float =
    maxOf(targetSide / width, targetSide / height)

private fun viewerDragScale(dragOffset: Offset): Float =
    (1f - (dragOffset.y / ViewerDismissScaleDistancePx)).coerceIn(0.86f, 1f)

private fun lerpOffset(start: Offset, stop: Offset, fraction: Float): Offset =
    Offset(
        x = lerpFloat(start.x, stop.x, fraction),
        y = lerpFloat(start.y, stop.y, fraction),
    )

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction.coerceIn(0f, 1f)

private fun Modifier.viewerSurfaceSize(
    item: MediaItem,
    viewportWidth: Dp,
    viewportHeight: Dp,
): Modifier {
    val width = item.width?.takeIf { it > 0 } ?: return fillMaxSize()
    val height = item.height?.takeIf { it > 0 } ?: return fillMaxSize()
    val itemAspectRatio = width.toFloat() / height.toFloat()
    val viewportAspectRatio = viewportWidth.value / viewportHeight.value

    return if (itemAspectRatio >= viewportAspectRatio) {
        fillMaxWidth().aspectRatio(itemAspectRatio)
    } else {
        fillMaxHeight().aspectRatio(itemAspectRatio)
    }
}

private fun viewerBackgroundAlpha(
    dragOffset: Offset,
    predictiveBackProgress: Float,
): Float {
    val dragProgress = (dragOffset.y / ViewerDismissBackgroundDistancePx).coerceIn(0f, 1f)
    val progress = maxOf(dragProgress, predictiveBackProgress.coerceIn(0f, 1f))
    return (1f - progress * 0.55f).coerceIn(0.45f, 1f)
}

private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) {
            return currentContext
        }
        currentContext = currentContext.baseContext
    }
    return null
}

private fun Image.hasGainmapCompat(): Boolean {
    if (Build.VERSION.SDK_INT < 34) {
        return false
    }

    val bitmap = (this as? BitmapImage)?.bitmap ?: return false
    return bitmap.hasGainmap()
}

private const val ViewerTimelinePrefetchSlots = 80
private const val ViewerDismissScaleDistancePx = 1_400f
private const val ViewerDismissBackgroundDistancePx = 420f
private const val ViewerBackgroundEnterDelayMillis = 210
private const val ViewerBackgroundEnterDurationMillis = 90
private const val ViewerBackgroundExitDurationMillis = 90
private const val ViewerEnterDurationMillis = 220
private const val ViewerSettleDurationMillis = 220
private val ViewerDismissThreshold = 112.dp
