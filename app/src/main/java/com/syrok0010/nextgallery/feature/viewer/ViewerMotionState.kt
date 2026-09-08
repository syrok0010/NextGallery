package com.syrok0010.nextgallery.ui.detail

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.domain.media.MediaId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
internal fun rememberViewerMotionState(
    initialMediaId: MediaId,
    currentMediaId: MediaId?,
    tileBoundsForMediaId: (MediaId) -> Rect?,
    onClose: () -> Unit,
): ViewerMotionState {
    val openingMediaId = rememberSaveable(saver = MediaIdSaver) { initialMediaId }
    val enterPending = rememberSaveable { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val currentTileBounds by rememberUpdatedState { currentMediaId?.let(tileBoundsForMediaId) }
    val closeViewer = rememberUpdatedState(onClose)
    val motion = remember {
        ViewerMotionState(
            openingMediaId = openingMediaId,
            enterPendingState = enterPending,
            scope = scope,
            tileBounds = { currentTileBounds() },
            onClose = closeViewer,
        )
    }

    LaunchedEffect(motion) {
        motion.showBackground()
    }
    LaunchedEffect(currentMediaId, motion.surfaceBounds) {
        motion.enter(currentMediaId)
    }
    PredictiveBackHandler(enabled = currentMediaId != null) { progress ->
        val closed = closeViewer.value
        try {
            progress.collect { motion.onBackProgress(it.progress) }
            motion.close(closed)
        } catch (error: CancellationException) {
            motion.cancelBack()
            throw error
        }
    }
    return motion
}

internal class ViewerMotionState(
    private val openingMediaId: MediaId,
    private val scope: CoroutineScope,
    private val tileBounds: () -> Rect?,
    private val onClose: State<() -> Unit>,
    private val enterPendingState: MutableState<Boolean>,
) {
    var surfaceBounds by mutableStateOf<Rect?>(null)
        private set
    private var predictiveBackProgress by mutableFloatStateOf(0f)
    private var enterPending by enterPendingState
    private var enterTarget by mutableStateOf<ViewerBoundsTransform?>(null)
    private var settleTarget by mutableStateOf<ViewerBoundsTransform?>(null)
    private val dragOffset = Animatable(Offset.Zero, Offset.VectorConverter)
    private val enterProgress = Animatable(if (enterPending) 0f else 1f)
    private val settleProgress = Animatable(0f)
    private val backgroundOpacity = Animatable(if (enterPending) 0f else 1f)

    val trackSurfaceBounds: Boolean
        get() = dragOffset.value == Offset.Zero && predictiveBackProgress == 0f &&
            enterTarget == null && settleTarget == null

    val chromeAllowed: Boolean
        get() = !enterPending && trackSurfaceBounds

    val backgroundAlpha: Float
        get() {
            val dragProgress = (dragOffset.value.y / ViewerDismissBackgroundDistancePx).coerceIn(0f, 1f)
            val progress = maxOf(dragProgress, predictiveBackProgress.coerceIn(0f, 1f))
            return backgroundOpacity.value * (1f - progress * 0.55f).coerceIn(0.45f, 1f)
        }

    fun surfaceTransform(mediaId: MediaId): ViewerSurfaceTransform = resolveViewerSurfaceTransform(
        dragOffset = dragOffset.value,
        predictiveBackProgress = predictiveBackProgress,
        enterPending = enterPending && mediaId == openingMediaId,
        enterTarget = enterTarget,
        enterProgress = enterProgress.value,
        settleTarget = settleTarget,
        settleProgress = settleProgress.value,
        predictiveTarget = surfaceBounds?.settleTarget(tileBounds(), Offset.Zero, 0f),
    )

    fun onSurfaceBoundsChange(bounds: Rect?) {
        surfaceBounds = bounds
    }

    suspend fun showBackground() {
        if (!enterPending) {
            backgroundOpacity.snapTo(1f)
            return
        }
        backgroundOpacity.snapTo(0f)
        backgroundOpacity.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = ViewerBackgroundEnterDurationMillis,
                delayMillis = ViewerBackgroundEnterDelayMillis,
            ),
        )
    }

    suspend fun enter(mediaId: MediaId?) {
        if (!enterPending || mediaId == null) return
        if (mediaId != openingMediaId) {
            enterProgress.snapTo(1f)
            enterPending = false
            return
        }
        val bounds = surfaceBounds ?: return
        val target = bounds.enterTarget(tileBounds())
        if (target == null) {
            enterProgress.snapTo(1f)
            enterPending = false
            return
        }
        enterProgress.snapTo(0f)
        enterTarget = target
        enterProgress.animateTo(1f, tween(durationMillis = ViewerEnterDurationMillis))
        enterTarget = null
        enterPending = false
    }

    fun close(onClosed: () -> Unit = onClose.value) {
        // Capture the destination and callback before starting the animation.
        val target = surfaceBounds?.settleTarget(tileBounds(), dragOffset.value, predictiveBackProgress)
        scope.launch {
            if (target != null) {
                settleProgress.snapTo(0f)
                settleTarget = target
                launch {
                    backgroundOpacity.animateTo(0f, tween(durationMillis = ViewerBackgroundExitDurationMillis))
                }
                settleProgress.animateTo(1f, tween(durationMillis = ViewerSettleDurationMillis))
            } else {
                backgroundOpacity.snapTo(0f)
            }
            onClosed()
        }
    }

    fun onBackProgress(progress: Float) {
        predictiveBackProgress = progress
    }

    fun cancelBack() {
        predictiveBackProgress = 0f
    }

    fun dragBy(amount: Offset): Boolean {
        val nextOffset = dragOffset.value + amount
        if (nextOffset.y < 0f || kotlin.math.abs(nextOffset.y) < kotlin.math.abs(nextOffset.x)) return false
        scope.launch { dragOffset.snapTo(nextOffset) }
        return true
    }

    fun cancelDrag() {
        predictiveBackProgress = 0f
        scope.launch { dragOffset.animateTo(Offset.Zero) }
    }

    fun endDrag(canClose: Boolean, thresholdPx: Float) {
        if (canClose && dragOffset.value.y > thresholdPx) {
            close()
        } else {
            scope.launch { dragOffset.animateTo(Offset.Zero) }
        }
    }
}

@Composable
internal fun Modifier.viewerDismissGestures(
    motion: ViewerMotionState,
    mediaId: MediaId?,
    canDrag: Boolean,
): Modifier {
    val thresholdPx = with(LocalDensity.current) { ViewerDismissThreshold.toPx() }
    return pointerInput(motion, mediaId, canDrag, thresholdPx) {
        detectDragGestures(
            onDragCancel = motion::cancelDrag,
            onDragEnd = { motion.endDrag(canClose = mediaId != null, thresholdPx = thresholdPx) },
        ) { change, amount ->
            if (canDrag && motion.dragBy(amount)) change.consume()
        }
    }
}

private val MediaIdSaver = Saver<MediaId, String>(
    save = { it.value },
    restore = { MediaId(it) },
)

private const val ViewerDismissBackgroundDistancePx = 420f
private const val ViewerBackgroundEnterDelayMillis = 210
private const val ViewerBackgroundEnterDurationMillis = 90
private const val ViewerBackgroundExitDurationMillis = 90
private const val ViewerEnterDurationMillis = 220
private const val ViewerSettleDurationMillis = 220
private val ViewerDismissThreshold = 112.dp
