package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

internal data class ViewerSurfaceTransform(
    val offset: Offset = Offset.Zero,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val clipShape: Shape? = null,
)

internal fun Modifier.viewerSurfaceTransform(transform: ViewerSurfaceTransform): Modifier = graphicsLayer {
    translationX = transform.offset.x
    translationY = transform.offset.y
    scaleX = transform.scale
    scaleY = transform.scale
    alpha = transform.alpha
    clip = transform.clipShape != null
    shape = transform.clipShape ?: RectangleShape
}

internal fun resolveViewerSurfaceTransform(
    dragOffset: Offset,
    predictiveBackProgress: Float,
    enterPending: Boolean,
    enterTarget: ViewerBoundsTransform?,
    enterProgress: Float,
    settleTarget: ViewerBoundsTransform?,
    settleProgress: Float,
    predictiveTarget: ViewerBoundsTransform?,
): ViewerSurfaceTransform {
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

    return when {
        settleTarget != null -> {
            val progress = settleProgress.coerceIn(0f, 1f)
            ViewerSurfaceTransform(
                offset = lerpOffset(settleTarget.startOffset, settleTarget.targetOffset, progress),
                scale = lerpFloat(settleTarget.startScale, settleTarget.targetScale, progress),
                clipShape = clipShape,
            )
        }
        predictiveProgress > 0f && predictiveTarget != null -> ViewerSurfaceTransform(
            offset = lerpOffset(Offset.Zero, predictiveTarget.targetOffset, predictiveProgress),
            scale = lerpFloat(1f, predictiveTarget.targetScale, predictiveProgress),
            clipShape = clipShape,
        )
        enterTarget != null -> {
            val progress = enterProgress.coerceIn(0f, 1f)
            ViewerSurfaceTransform(
                offset = lerpOffset(enterTarget.startOffset, enterTarget.targetOffset, progress),
                scale = lerpFloat(enterTarget.startScale, enterTarget.targetScale, progress),
                clipShape = clipShape,
            )
        }
        enterPending -> ViewerSurfaceTransform(alpha = 0f)
        else -> ViewerSurfaceTransform(offset = dragOffset, scale = dragScale)
    }
}

private fun animatedLocalClipSize(
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

internal fun Rect.settleTarget(
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

internal fun Rect.enterTarget(tileBounds: Rect?): ViewerBoundsTransform? {
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

private fun Rect.viewerCropScale(targetSide: Float): Float =
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

private const val ViewerDismissScaleDistancePx = 1_400f
