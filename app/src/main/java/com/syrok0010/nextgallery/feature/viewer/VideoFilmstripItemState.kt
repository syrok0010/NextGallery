package com.syrok0010.nextgallery.feature.viewer

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import kotlinx.coroutines.CoroutineScope

/** Geometry supplied by the strip; decoding and playback policy do not depend on LazyRow. */
internal data class VideoFilmstripPlacement(
    val expanded: Boolean,
    val selected: Boolean,
    val activeWidth: Boolean,
    val viewportWidth: Dp,
    val fraction: Float,
    val scrolling: Boolean,
)

internal class VideoFilmstripItemState(
    private val item: MediaItem,
    private val provider: VideoFrameProvider,
    private val playback: FilmstripPlayback,
    private val scope: CoroutineScope,
    private val uptimeMillis: () -> Long = SystemClock::uptimeMillis,
) {
    private val sources = VideoSources.from(item.assetRef)
    private var source: String? = null
    private var loader by mutableStateOf<VideoFilmstripLoader?>(null)
    val frames get() = loader?.state ?: VideoFilmstripState()
    private var expanded = false

    fun update(placement: VideoFilmstripPlacement) {
        expanded = placement.expanded
        if (!expanded) {
            close()
            return
        }
        val nextSource = playback.sourceFor(item.mediaId) ?: sources.primary
        if (loader == null || nextSource != source) {
            close()
            source = nextSource
            loader = VideoFilmstripLoader(provider, nextSource, scope).also { it.retry() }
        }
        loader?.scroll(
            placement.fraction, placement.scrolling && placement.selected,
            uptimeMillis(),
            { position, final -> playback.seek(item.mediaId, position, final) },
            { playback.finish(item.mediaId) },
        )
    }

    fun seek(fraction: Float, movePlayhead: (Float) -> Unit): Boolean {
        if (!expanded) return false
        val position = loader?.seek(fraction, uptimeMillis()) ?: return false
        movePlayhead(fraction.coerceIn(0f, 1f))
        playback.seek(item.mediaId, position, true)
        return true
    }

    fun retry() { loader?.retry() }

    fun close() {
        if (loader != null) playback.finish(item.mediaId)
        loader?.close()
        loader = null
        source = null
    }
}
