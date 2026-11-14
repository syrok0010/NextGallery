package com.syrok0010.nextgallery.feature.viewer

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
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

    /** Coming in; without a [target] the surface is already at rest and only the rest fades in. */
    data class Opening(val target: ViewerBoundsTransform?) : ViewerPhase

    /** Open; the user may be dragging it down, or it may be going back to rest after a drag. */
    data object Shown : ViewerPhase

    data class PredictiveBack(val progress: Float) : ViewerPhase

    /**
     * Settling onto the tile, then gone; nothing leads out of here. [background] and [chrome] are
     * the alphas the closing started from.
     */
    data class Closing(
        val target: ViewerBoundsTransform?,
        val background: Float,
        val chrome: Float,
    ) : ViewerPhase
}

/**
 * The surface's way from its tile into the viewer and back. One [phase] says where it is; every
 * event is a transition from it, and each new animation replaces the one before, so a cut-short
 * animation leaves nothing behind.
 *
 * The surface, the background and the chrome follow one animation, each over its own part of it,
 * so they come in one after another: the background darkens while the surface grows, and the
 * chrome shows over the background. Closing runs the other way round.
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

    /**
     * How far the opening or the closing is, from 0 to 1: the share of the opening's time, or of
     * the closing surface's way onto its tile.
     */
    private var progress by mutableFloatStateOf(0f)
    private var dragOffset by mutableStateOf(Offset.Zero)

    /** The surface's running animation; the next one cancels it. */
    private var surfaceAnimation: Job? = null

    val trackSurfaceBounds: Boolean
        get() = when (phase) {
            ViewerPhase.Hidden -> true
            ViewerPhase.Shown -> dragOffset == Offset.Zero
            else -> false
        }

    val backgroundAlpha: Float
        get() = when (val phase = phase) {
            ViewerPhase.Hidden -> 0f

            is ViewerPhase.Opening -> ViewerTimeline.backgroundOpening.of(progress)

            ViewerPhase.Shown, is ViewerPhase.PredictiveBack ->
                1f - pull() * ViewerTimeline.PulledBackgroundDim

            is ViewerPhase.Closing -> phase.background * (1f - progress)
        }.coerceIn(0f, 1f)

    /** The header, the filmstrip and the actions: part of the surface, they come and go with it. */
    val chromeAlpha: Float
        get() = when (val phase = phase) {
            ViewerPhase.Hidden -> 0f

            is ViewerPhase.Opening -> ViewerTimeline.chromeOpening.of(progress)

            ViewerPhase.Shown, is ViewerPhase.PredictiveBack ->
                1f - ViewerTimeline.chromePull.of(pull())

            is ViewerPhase.Closing ->
                phase.chrome *
                    (
                        1f -
                            ViewerTimeline.chromeClosing.of(
                                progress,
                            )
                        )
        }.coerceIn(0f, 1f)

    fun surfaceTransform(mediaId: MediaId): ViewerSurfaceTransform =
        when (val phase = phase) {
            ViewerPhase.Hidden -> if (mediaId == openingMediaId) {
                ViewerSurfaceTransform(alpha = 0f)
            } else {
                ViewerSurfaceTransform()
            }

            is ViewerPhase.Opening -> phase.target?.opening(openedSurface())
                ?: ViewerSurfaceTransform()

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

            // The user moved on before the opening ended: the other object just shows, while the
            // background and the chrome keep coming in.
            is ViewerPhase.Opening -> if (mediaId != null && mediaId != openingMediaId) {
                phase = ViewerPhase.Opening(target = null)
            }

            else -> Unit
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
                        ?.let { settle ->
                            from.target?.let { settle.startingFrom(it, openedSurface()) } ?: settle
                        }

                ViewerPhase.Shown -> bounds.settleTarget(tile, dragOffset, 0f)

                is ViewerPhase.PredictiveBack -> bounds.settleTarget(
                    tile,
                    Offset.Zero,
                    from.progress,
                )
            }
        }
        val background = backgroundAlpha
        val chrome = chromeAlpha
        progress = 0f
        phase = ViewerPhase.Closing(settle, background, chrome)
        animateSurface {
            if (settle == null) {
                // Without a tile to settle on, the viewer just fades out.
                animate(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = tween(
                        durationMillis = ViewerTimeline.FadeOutMillis,
                        easing = LinearEasing,
                    ),
                ) { value, _ -> progress = value }
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

    /**
     * Opens once the object it opened on is current and its surface is measured. The surface
     * grows out of its tile only if it is the object the viewer opened on and the tile is known.
     */
    private fun open() {
        val mediaId = currentMediaId ?: return
        val bounds = surfaceBounds ?: return
        val target = if (mediaId == openingMediaId) bounds.enterTarget(tileBounds()) else null
        progress = 0f
        phase = ViewerPhase.Opening(target)
        animateSurface {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = ViewerTimeline.OpeningMillis,
                    easing = LinearEasing,
                ),
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

    /** How far the opening has brought the surface out of its tile. */
    private fun openedSurface(): Float =
        FastOutSlowInEasing.transform(ViewerTimeline.surfaceOpening.of(progress))

    /** The surface settled on its tile from rest, where a predictive back leads it. */
    private fun restingTarget(): ViewerBoundsTransform? =
        surfaceBounds?.settleTarget(tileBounds(), Offset.Zero, 0f)

    /** How much the user has pulled the open viewer away, from 0 to 1. */
    private fun pull(): Float =
        when (val phase = phase) {
            ViewerPhase.Shown -> dragOffset.y / ViewerDismissBackgroundDistancePx
            is ViewerPhase.PredictiveBack -> phase.progress
            else -> 0f
        }.coerceIn(0f, 1f)
}

/**
 * When each part of the viewer comes and goes. The opening is one linear clock; the surface,
 * the background and the chrome each take their own window of it. The closing and a pull take
 * windows of how far the surface has gone.
 */
private object ViewerTimeline {
    const val OpeningMillis = 300

    /** The surface grows out of its tile over the first 220 ms, as it always has. */
    val surfaceOpening = window(0, 220)

    /** The background is dark before the chrome starts to show over it. */
    val backgroundOpening = window(0, 150)
    val chromeOpening = window(150, OpeningMillis)

    /** The chrome is gone in the first part of the closing; the background goes with all of it. */
    val chromeClosing = ProgressWindow(0f, 0.7f)

    /** The chrome is gone halfway to a closing pull. */
    val chromePull = ProgressWindow(0f, 0.5f)

    /** How much a full pull lightens the background. */
    const val PulledBackgroundDim = 0.55f

    /** The closing without a tile to settle on. */
    const val FadeOutMillis = 150

    private fun window(fromMillis: Int, toMillis: Int) =
        ProgressWindow(fromMillis / OpeningMillis.toFloat(), toMillis / OpeningMillis.toFloat())
}

/** A part of an animation, from [start] to [end] of it, that runs from 0 to 1 on its own. */
private class ProgressWindow(private val start: Float, private val end: Float) {
    fun of(progress: Float): Float = ((progress - start) / (end - start)).coerceIn(0f, 1f)
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
private val ViewerDismissThreshold = 112.dp
private val ViewerDismissFlingVelocity = 800.dp
internal val ViewerDetailsFlingVelocity = 400.dp
