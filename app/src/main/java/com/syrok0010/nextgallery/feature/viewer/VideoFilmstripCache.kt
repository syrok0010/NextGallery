package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaAssetRef

internal data class VideoFrameCacheKey(val asset: MediaAssetRef, val etag: String?)

/** Recently completed strips only. Owned by one viewer; never recycles displayed bitmaps. */
internal class VideoFilmstripCache<T>(
    private val maxBytes: Long,
    private val sizeBytes: (T) -> Int,
) {
    private val entries = LinkedHashMap<VideoFrameCacheKey, VideoFilmstripState<T>>(16, 0.75f, true)
    private var bytes = 0L

    operator fun get(key: VideoFrameCacheKey): VideoFilmstripState<T>? = entries[key]

    fun put(key: VideoFrameCacheKey, state: VideoFilmstripState<T>) {
        if (state.phase != VideoFilmstripPhase.Ready || state.showsPoster) return
        val size = size(state)
        if (size > maxBytes) return
        entries.remove(key)?.let { bytes -= size(it) }
        entries[key] = state
        bytes += size
        val iterator = entries.values.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            bytes -= size(iterator.next())
            iterator.remove()
        }
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun size(state: VideoFilmstripState<T>): Long =
        state.frames.values.sumOf { sizeBytes(it).toLong() }
}
