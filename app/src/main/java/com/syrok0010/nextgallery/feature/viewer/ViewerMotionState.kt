package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.core.media.MediaId
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@Composable
internal fun rememberViewerMotionState(
    initialMediaId: MediaId,
    currentMediaId: MediaId?,
    tileBoundsForMediaId: (MediaId) -> Rect?,
    onClose: () -> Unit,
    backEnabled: Boolean = true,
): ViewerMotionState {
    val openingMediaId = rememberSaveable(saver = MediaIdSaver) { initialMediaId }
    val opened = rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val currentTileBounds by rememberUpdatedState { currentMediaId?.let(tileBoundsForMediaId) }
    val closeViewer = rememberUpdatedState(onClose)
    val motion = remember {
        ViewerMotionState(
            openingMediaId = openingMediaId,
            openedState = opened,
            scope = scope,
            tileBounds = { currentTileBounds() },
            onClose = closeViewer,
        )
    }

    LaunchedEffect(motion) {
        motion.showBackground()
    }
    LaunchedEffect(currentMediaId) {
        motion.onCurrentMediaChange(currentMediaId)
    }
    PredictiveBackHandler(enabled = backEnabled && currentMediaId != null) { progress ->
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

/** Where the viewer is between the tile it opened from and the tile it closes onto. */
private sealed interface ViewerPhase {
    /** Waiting for the surface to be measured, to grow it out of its tile. */
    data object Hidden : ViewerPhase

    data class Opening(val target: ViewerBoundsTransform) : ViewerPhase

    /** Open; the user may be dragging it down, or it may be going back to rest after a drag. */
    data object Shown : ViewerPhase

    data class PredictiveBack(val progress: Float) : ViewerPhase

    /**
     * Settling onto the tile, then gone; nothing leads out of here. [dim] and [openness] are as
     * they were when the closing started.
     */
    data class Closing(
        val target: ViewerBoundsTransform?,
        val dim: Float,
        val openness: Float,
    ) : ViewerPhase
}

/**
 * The surface's way from its tile into the viewer and back. One [phase] says where it is; every
 * event is a transition from it, and each new animation replaces the one before, so a cut-short
 * animation leaves nothing behind.
 */
internal class ViewerMotionState(
    private val openingMediaId: MediaId,
    private val scope: CoroutineScope,
    private val tileBounds: () -> Rect?,
    private val onClose: State<() -> Unit>,
    openedState: MutableState<Boolean>,
) {
    /** Survives recreation, so a rotated viewer does not open again. */
    private var opened by openedState
    private var phase by mutableStateOf<ViewerPhase>(
        if (opened) ViewerPhase.Shown else ViewerPhase.Hidden,
    )
    private var currentMediaId: MediaId? = null
    var surfaceBounds by mutableStateOf<Rect?>(null)
        private set

    /** How far the opening or the closing is. */
    private var progress by mutableFloatStateOf(0f)
    private var dragOffset by mutableStateOf(Offset.Zero)
    private var background by mutableFloatStateOf(if (opened) 1f else 0f)

    /** The surface's running animation; the next one cancels it. */
    private var surfaceAnimation: Job? = null
    private var backgroundAnimation: Job? = null

    val trackSurfaceBounds: Boolean
        get() = when (phase) {
            ViewerPhase.Hidden -> true
            ViewerPhase.Shown -> dragOffset == Offset.Zero
            else -> false
        }

    /**
     * How far the viewer has come out of its tile, from 0 at the tile to 1 at rest. The chrome
     * fades in with the opening, and out as the viewer is pulled away or closes.
     */
    val openness: Float
        get() = when (val phase = phase) {
            ViewerPhase.Hidden -> 0f
            is ViewerPhase.Opening -> progress
            ViewerPhase.Shown, is ViewerPhase.PredictiveBack -> 1f - dim()
            // Without a tile to settle on, the viewer leaves with its background.
            is ViewerPhase.Closing -> phase.openness * if (phase.target != null) 1f - progress else background
        }.coerceIn(0f, 1f)

    val backgroundAlpha: Float
        get() = background * (1f - dim() * 0.55f).coerceIn(0.45f, 1f)

    fun surfaceTransform(mediaId: MediaId): ViewerSurfaceTransform =
        when (val phase = phase) {
            ViewerPhase.Hidden -> if (mediaId == openingMediaId) {
                ViewerSurfaceTransform(alpha = 0f)
            } else {
                ViewerSurfaceTransform()
            }

            is ViewerPhase.Opening -> phase.target.opening(progress)

            ViewerPhase.Shown -> draggedSurface(dragOffset)

            is ViewerPhase.PredictiveBack -> restingTarget()?.predictiveBack(phase.progress)
                ?: ViewerSurfaceTransform()

            is ViewerPhase.Closing -> phase.target?.closing(progress)
                ?: draggedSurface(dragOffset)
        }

    fun onSurfaceBoundsChange(bounds: Rect?) {
        surfaceBounds = bounds
        if (phase == ViewerPhase.Hidden) open()
    }

    fun onCurrentMediaChange(mediaId: MediaId?) {
        currentMediaId = mediaId
        when (phase) {
            ViewerPhase.Hidden -> open()

            // The user moved on before the opening ended: the other object just shows.
            is ViewerPhase.Opening -> if (mediaId != null && mediaId != openingMediaId) {
                surfaceAnimation?.cancel()
                rest()
            }

            else -> Unit
        }
    }

    fun showBackground() {
        if (opened) return
        backgroundAnimation = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ViewerBackgroundEnterDurationMillis,
                    delayMillis = ViewerBackgroundEnterDelayMillis,
                ),
            ) { value, _ -> background = value }
        }
    }

    /** Settles the surface on its tile, then calls [onClosed]; only the first call does. */
    fun close(onClosed: () -> Unit = onClose.value, initialVelocity: Velocity = Velocity.Zero) {
        val from = phase
        if (from is ViewerPhase.Closing) return
        val settle = surfaceBounds?.let { bounds ->
            val tile = tileBounds()
            when (from) {
                ViewerPhase.Hidden, is ViewerPhase.Closing -> null

                // The surface leaves from where the opening has brought it.
                is ViewerPhase.Opening ->
                    bounds
                        .settleTarget(tile, Offset.Zero, 0f)
                        ?.startingFrom(from.target, progress)

                ViewerPhase.Shown -> bounds.settleTarget(tile, dragOffset, 0f)

                is ViewerPhase.PredictiveBack -> bounds.settleTarget(
                    tile,
                    Offset.Zero,
                    from.progress,
                )
            }
        }
        val dim = dim()
        val openness = openness
        progress = 0f
        phase = ViewerPhase.Closing(settle, dim, openness)
        backgroundAnimation?.cancel()
        animateSurface {
            val fade = launch {
                animate(
                    initialValue = background,
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = ViewerBackgroundExitDurationMillis),
                ) { value, _ -> background = value }
            }
            if (settle == null) {
                fade.join()
            } else {
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    initialVelocity = settle.velocityAlongPath(initialVelocity),
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessMedium,
                    ),
                ) { value, _ -> progress = value }
            }
            onClosed()
        }
    }

    fun onBackProgress(progress: Float) {
        if (phase == ViewerPhase.Shown || phase is ViewerPhase.PredictiveBack) {
            phase = ViewerPhase.PredictiveBack(progress)
        }
    }

    fun cancelBack() {
        if (phase is ViewerPhase.PredictiveBack) phase = ViewerPhase.Shown
    }

    fun dragBy(amount: Offset): Boolean {
        if (phase != ViewerPhase.Shown) return false
        val next = dragOffset + amount
        if (next.y < 0f || abs(next.y) < abs(next.x)) return false
        // A drag the viewer takes stops the surface going back to rest.
        surfaceAnimation?.cancel()
        dragOffset = next
        return true
    }

    fun cancelDrag() {
        if (phase == ViewerPhase.Shown) returnToRest(spring())
    }

    fun endDrag(
        canClose: Boolean,
        thresholdPx: Float,
        flingVelocityPx: Float = Float.MAX_VALUE,
        velocity: Velocity = Velocity.Zero,
    ) {
        if (phase != ViewerPhase.Shown) return
        val isFlingClose = velocity.y > flingVelocityPx && dragOffset.y > 0f
        if (canClose && (dragOffset.y > thresholdPx || isFlingClose)) {
            close(initialVelocity = velocity)
        } else {
            returnToRest(
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
                ),
                Offset(velocity.x, velocity.y),
            )
        }
    }

    /** Opens once the object it opened on is current and its surface is measured. */
    private fun open() {
        val mediaId = currentMediaId ?: return
        if (mediaId != openingMediaId) return rest()
        val bounds = surfaceBounds ?: return
        val target = bounds.enterTarget(tileBounds()) ?: return rest()
        progress = 0f
        phase = ViewerPhase.Opening(target)
        animateSurface {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = ViewerEnterDurationMillis),
            ) { value, _ -> progress = value }
            rest()
        }
    }

    private fun rest() {
        phase = ViewerPhase.Shown
        opened = true
    }

    private fun returnToRest(spec: AnimationSpec<Offset>, velocity: Offset = Offset.Zero) {
        animateSurface {
            animate(
                typeConverter = Offset.VectorConverter,
                initialValue = dragOffset,
                targetValue = Offset.Zero,
                initialVelocity = velocity,
                animationSpec = spec,
            ) { value, _ -> dragOffset = value }
        }
    }

    private fun animateSurface(block: suspend CoroutineScope.() -> Unit) {
        surfaceAnimation?.cancel()
        surfaceAnimation = scope.launch(block = block)
    }

    /** The surface settled on its tile from rest, where a predictive back leads it. */
    private fun restingTarget(): ViewerBoundsTransform? =
        surfaceBounds?.settleTarget(tileBounds(), Offset.Zero, 0f)

    /** How much the user has pulled the viewer away, from 0 to 1. */
    private fun dim(): Float =
        when (val phase = phase) {
            ViewerPhase.Shown -> dragOffset.y / ViewerDismissBackgroundDistancePx
            is ViewerPhase.PredictiveBack -> phase.progress
            is ViewerPhase.Closing -> phase.dim
            else -> 0f
        }.coerceIn(0f, 1f)
}

@Composable
internal fun Modifier.viewerDismissGestures(
    motion: ViewerMotionState,
    mediaId: MediaId?,
    canDrag: Boolean,
    details: ViewerDetailsState? = null,
): Modifier {
    val thresholdPx = with(LocalDensity.current) { ViewerDismissThreshold.toPx() }
    val flingVelocityPx = with(LocalDensity.current) { ViewerDismissFlingVelocity.toPx() }
    val detailsFlingPx = with(LocalDensity.current) { ViewerDetailsFlingVelocity.toPx() }
    val velocityTracker = remember { VelocityTracker() }

    return pointerInput(motion, mediaId, canDrag, details, thresholdPx, flingVelocityPx) {
        // Decided by the first movement: up, or any vertical drag while the sheet is out, moves
        // the details sheet; down on a closed sheet dismisses the viewer as before.
        var draggingDetails: Boolean? = null
        detectDragGestures(
            onDragStart = {
                velocityTracker.resetTracking()
                draggingDetails = null
            },
            onDragCancel = {
                velocityTracker.resetTracking()
                if (draggingDetails == true) {
                    details?.settle(
                        0f,
                        detailsFlingPx,
                    )
                } else {
                    motion.cancelDrag()
                }
            },
            onDragEnd = {
                val velocity = velocityTracker.calculateVelocity()
                velocityTracker.resetTracking()
                if (draggingDetails == true) {
                    details?.settle(velocity.y, detailsFlingPx)
                } else {
                    motion.endDrag(
                        canClose = mediaId != null,
                        thresholdPx = thresholdPx,
                        flingVelocityPx = flingVelocityPx,
                        velocity = velocity,
                    )
                }
            },
        ) { change, amount ->
            velocityTracker.addPosition(change.uptimeMillis, change.position)
            if (!canDrag) return@detectDragGestures
            val toDetails = draggingDetails ?: (
                details != null &&
                    (details.engaged || (amount.y < 0f && abs(amount.y) > abs(amount.x)))
                ).also { draggingDetails = it }
            if (toDetails) {
                details?.dragBy(amount.y)
                change.consume()
            } else if (motion.dragBy(amount)) {
                change.consume()
            }
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
private val ViewerDismissThreshold = 112.dp
private val ViewerDismissFlingVelocity = 800.dp
internal val ViewerDetailsFlingVelocity = 400.dp
