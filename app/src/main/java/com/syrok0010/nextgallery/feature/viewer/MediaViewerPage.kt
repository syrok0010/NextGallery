package com.syrok0010.nextgallery.feature.viewer

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.BitmapImage
import coil3.Image
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.images.MediaAssetImage
import com.syrok0010.nextgallery.feature.images.MediaImagePurpose
import com.syrok0010.nextgallery.feature.images.MediaImageRequestFactory
import com.syrok0010.nextgallery.feature.images.rememberFallbackImageRequest
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.koin.compose.koinInject

@Composable
internal fun MediaViewerPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    surfaceTransform: ViewerSurfaceTransform,
    trackSurfaceBounds: Boolean,
    onToggleChrome: () -> Unit,
    onActivePageStateChange: (ActiveViewerPageState) -> Unit,
    onSurfaceBoundsChange: (Rect?) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit = {},
    controlsVisible: Boolean = true,
) {
    val requestFactory: MediaImageRequestFactory = koinInject()
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
            Modifier.viewerSurfaceTransform(surfaceTransform)
        } else {
            Modifier
        }

        DisposableEffect(item.mediaId, isCurrentPage) {
            onDispose {
                if (isCurrentPage) {
                    onSurfaceBoundsChange(null)
                }
            }
        }

        if (item.isVideo) {
            if (isCurrentPage) {
                SideEffect(item.mediaId) {
                    onActivePageStateChange(ActiveViewerPageState(hasHdr = false, canDragDown = true))
                }
            }

            val localAsset = when (val asset = item.assetRef) {
                is MediaAssetRef.LocalContent -> asset
                is MediaAssetRef.LocalFirst -> asset.local
                is MediaAssetRef.MemoriesFile -> null
            }
            if (isCurrentPage && localAsset != null) {
                VideoPlaybackSurface(
                    item = item,
                    contentUri = localAsset.contentUri,
                    modifier = Modifier.fillMaxSize(),
                    controlsVisible = controlsVisible,
                    contentModifier = contentSurfaceModifier
                        .then(pageTransformModifier)
                        .onGloballyPositioned { coordinates ->
                            if (trackSurfaceBounds) onSurfaceBoundsChange(coordinates.boundsInRoot())
                        },
                    onToggleChrome = onToggleChrome,
                    onFullscreenChanged = onFullscreenChanged,
                )
            } else Box(
                modifier = contentSurfaceModifier
                    .then(pageTransformModifier)
                    .onGloballyPositioned { coordinates ->
                        if (trackSurfaceBounds) {
                            onSurfaceBoundsChange(coordinates.boundsInRoot())
                        }
                    },
            ) {
                MediaAssetImage(
                    item = item,
                    purpose = MediaImagePurpose.DetailPreview,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )

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
            var hasGainmap by remember(item.mediaId) { mutableStateOf(false) }
            val isZoomedOut by remember(zoomableState) {
                derivedStateOf { (zoomableState.zoomFraction ?: 0f) <= 0.01f }
            }

            if (isCurrentPage) {
                SideEffect(item.mediaId, hasGainmap, isZoomedOut) {
                    onActivePageStateChange(
                        ActiveViewerPageState(
                            hasHdr = hasGainmap,
                            canDragDown = isZoomedOut,
                        ),
                    )
                }
            }

            val originalPlan = requestFactory.rememberPlan(item, MediaImagePurpose.Original)
            val originalRequest = rememberFallbackImageRequest(
                context = context,
                plan = originalPlan,
                onSuccess = { image -> hasGainmap = image.hasGainmapCompat() },
                onError = { hasGainmap = false },
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .then(pageTransformModifier),
            ) {
                Box(
                    modifier = contentSurfaceModifier
                        .align(Alignment.Center)
                        .onGloballyPositioned { coordinates ->
                            if (trackSurfaceBounds) {
                                onSurfaceBoundsChange(coordinates.boundsInRoot())
                            }
                        },
                ) {
                    if (item.assetRef is MediaAssetRef.MemoriesFile) {
                        MediaAssetImage(
                            item = item,
                            purpose = MediaImagePurpose.DetailPreview,
                            contentDescription = item.displayName,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
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

private fun Image.hasGainmapCompat(): Boolean {
    if (Build.VERSION.SDK_INT < 34) {
        return false
    }

    val bitmap = (this as? BitmapImage)?.bitmap ?: return false
    return bitmap.hasGainmap()
}
