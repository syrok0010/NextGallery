package com.syrok0010.nextgallery.feature.viewer

import android.os.Build
import androidx.annotation.OptIn
import androidx.compose.ui.platform.testTag
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import coil3.BitmapImage
import coil3.Image
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

internal const val VideoPlaybackSurfaceTestTag = "video_playback_surface"

@OptIn(UnstableApi::class)
@Composable
internal fun MediaViewerPage(
    item: MediaItem,
    isCurrentPage: Boolean,
    surfaceTransform: ViewerSurfaceTransform,
    trackSurfaceBounds: Boolean,
    onToggleChrome: () -> Unit,
    onActivePageStateChange: (ActiveViewerPageState) -> Unit,
    onSurfaceBoundsChange: (Rect?) -> Unit,
    playbackController: VideoPlaybackController? = null,
) {
    val requestFactory: MediaImageRequestFactory = koinInject()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .then(if (item.isVideo) Modifier.clickable(onClick = onToggleChrome) else Modifier),
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

        Box(
            modifier = contentSurfaceModifier
                .then(pageTransformModifier)
                .onGloballyPositioned { coordinates ->
                    if (trackSurfaceBounds) onSurfaceBoundsChange(coordinates.boundsInRoot())
                },
        ) {
            if (item.isVideo || item.assetRef is MediaAssetRef.MemoriesFile) {
                MediaAssetImage(
                    item = item,
                    purpose = MediaImagePurpose.DetailPreview,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (item.isVideo && isCurrentPage && playbackController != null) {
                Box(Modifier.fillMaxSize().testTag(VideoPlaybackSurfaceTestTag)) {
                    if (playbackController.state.phase != VideoPlaybackPhase.Poster &&
                        playbackController.state.phase != VideoPlaybackPhase.Error
                    ) {
                        ContentFrame(
                            player = playbackController.player,
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        val activePageState = if (item.isVideo) {
            ActiveViewerPageState(hasHdr = false, canDragDown = true)
        } else {
            val context = LocalContext.current
            val zoomableState = rememberZoomableState()
            val zoomableImageState = rememberZoomableImageState(zoomableState)
            var hasGainmap by remember(item.mediaId) { mutableStateOf(false) }
            val isZoomedOut by remember(zoomableState) {
                derivedStateOf { (zoomableState.zoomFraction ?: 0f) <= 0.01f }
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
                ZoomableAsyncImage(
                    model = originalRequest,
                    contentDescription = item.displayName,
                    modifier = Modifier.fillMaxSize(),
                    state = zoomableImageState,
                    contentScale = ContentScale.Fit,
                    onClick = { onToggleChrome() },
                )
            }
            ActiveViewerPageState(hasHdr = hasGainmap, canDragDown = isZoomedOut)
        }
        if (isCurrentPage) {
            SideEffect(item.mediaId, activePageState) {
                onActivePageStateChange(activePageState)
            }
        }
    }
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
