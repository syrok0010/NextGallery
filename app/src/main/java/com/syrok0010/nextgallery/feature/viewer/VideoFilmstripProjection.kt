package com.syrok0010.nextgallery.feature.viewer

import kotlin.math.log10
import kotlin.math.roundToLong

internal enum class VideoFilmstripPhase { Loading, Ready, Degraded }

internal data class VideoFilmstripState<T>(
    val positionsMillis: List<Long> = emptyList(),
    val frames: Map<Int, T> = emptyMap(),
    val phase: VideoFilmstripPhase = VideoFilmstripPhase.Loading,
) {
    // Missing frames retain the media poster, including before duration is known.
    val showsPoster: Boolean get() = frames.size < positionsMillis.size || frames.isEmpty()
}

/** Bounded frame plan and gesture policy; independent of Android, decoding and the player. */
internal class VideoFilmstripProjection<T> {
    var state = VideoFilmstripState<T>()
        private set
    private var lastSeekAt: Long? = null

    fun durationKnown(durationMillis: Long) {
        if (durationMillis <= 0) {
            failed()
            return
        }
        val end = durationMillis - 1
        val frameCount = videoFilmstripFrameCount(durationMillis)
        state = VideoFilmstripState(positionsMillis = List(frameCount) { index ->
            (end.toDouble() * index / (frameCount - 1)).toLong()
        })
    }

    fun frameReady(index: Int, frame: T) {
        if (index !in state.positionsMillis.indices) return
        state = state.copy(frames = state.frames + (index to frame))
    }

    fun finished() {
        state = state.copy(phase = if (!state.showsPoster) VideoFilmstripPhase.Ready else VideoFilmstripPhase.Degraded)
    }

    fun failed() {
        state = state.copy(phase = VideoFilmstripPhase.Degraded)
    }

    fun retry() {
        state = VideoFilmstripState()
        lastSeekAt = null
    }

    fun scrub(fraction: Float, uptimeMillis: Long, finished: Boolean = false): Long? {
        val end = state.positionsMillis.lastOrNull() ?: return null
        if (!fraction.isFinite()) return null
        val last = lastSeekAt
        if (!finished && last != null && uptimeMillis - last < SeekIntervalMillis) return null
        lastSeekAt = if (finished) null else uptimeMillis
        return (end.toDouble() * fraction.coerceIn(0f, 1f)).roundToLong()
    }

    companion object {
        const val SeekIntervalMillis = 100L
    }
}

internal fun videoFilmstripFrameCount(durationMillis: Long): Int {
    val seconds = (durationMillis / 1000).coerceAtLeast(1)
    return (7 * log10(seconds.toDouble())).toInt().coerceAtLeast(3)
}
