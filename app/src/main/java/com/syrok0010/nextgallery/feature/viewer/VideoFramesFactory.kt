package com.syrok0010.nextgallery.feature.viewer

import android.content.Context
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory

/** Shared dependencies; each expanded card gets its own extraction request identity. */
internal class VideoFramesFactory(
    private val context: Context,
    private val playerFactory: VideoPlayerFactory,
) {
    fun create(fallbackUri: String?): VideoFrameProvider {
        val clientId = java.util.UUID.randomUUID().toString()
        return FallbackVideoFrames(
            local = LocalVideoFrames(context),
            remote = RemoteVideoFrames(context) { playerFactory.mediaSourceFactory(context) },
            fallbackUri = fallbackUri,
            qualities = { uri -> playerFactory.qualities(uri, clientId) },
        )
    }
}
