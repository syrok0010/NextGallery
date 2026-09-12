package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaId
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Owned by the current MediaId. The surface attaches only for its active playback session. */
internal class VideoScrubController(private val mediaId: MediaId?) : FilmstripPlayback {
    var sourceUri by mutableStateOf<String?>(null)

    var dispatch: ((VideoPlaybackInput) -> Unit)? = null

    override fun sourceFor(id: MediaId): String? = sourceUri.takeIf { id == mediaId }

    override fun seek(id: MediaId, position: Long, finished: Boolean) {
        if (id != mediaId) return
        dispatch?.invoke(VideoPlaybackInput.ScrubTo(position))
        if (finished) finish(id)
    }

    override fun finish(id: MediaId) {
        if (id == mediaId) dispatch?.invoke(VideoPlaybackInput.EndScrub)
    }
}
