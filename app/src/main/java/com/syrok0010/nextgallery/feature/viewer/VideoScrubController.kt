package com.syrok0010.nextgallery.feature.viewer

/** Owned by the current MediaId. The surface attaches only for its active playback session. */
internal class VideoScrubController {
    var dispatch: ((VideoPlaybackInput) -> Unit)? = null

    fun seek(positionMillis: Long, finished: Boolean) {
        dispatch?.invoke(VideoPlaybackInput.ScrubTo(positionMillis))
        if (finished) finish()
    }

    fun finish() { dispatch?.invoke(VideoPlaybackInput.EndScrub) }
}
