package com.syrok0010.nextgallery.feature.viewer

import android.content.Context
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.feature.viewer.playback.VideoSources
import com.syrok0010.nextgallery.feature.viewer.playback.VideoPlayerFactory

/** Shared dependencies; each expanded card gets its own extraction request identity. */
internal class VideoFramesFactory(
    private val context: Context,
    private val playerFactory: VideoPlayerFactory,
) {
    fun sources(asset: MediaAssetRef): VideoSources = playerFactory.sources(asset)

    fun create(sources: VideoSources): VideoFrameProvider {
        val clientId = java.util.UUID.randomUUID().toString()
        return FallbackVideoFrames(
            local = LocalVideoFrames(context),
            remote = RemoteVideoFrames(context) { playerFactory.mediaSourceFactory(context) },
            fallbackUri = sources.fallback,
            originalUri = sources.remoteOriginal?.uri,
            qualities = { playerFactory.qualities(checkNotNull(sources.remoteOriginal), clientId) },
        )
    }
}
