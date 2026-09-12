package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Owned by the current MediaId. The surface attaches only for its active playback session. */
internal class VideoScrubController {
    var sourceUri by androidx.compose.runtime.mutableStateOf<String?>(null)

    var dispatch: ((VideoPlaybackInput) -> Unit)? = null

    fun seek(positionMillis: Long, finished: Boolean) {
        dispatch?.invoke(VideoPlaybackInput.ScrubTo(positionMillis))
        if (finished) finish()
    }

    fun finish() { dispatch?.invoke(VideoPlaybackInput.EndScrub) }
}
