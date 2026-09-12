package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A frame failure never dispatches a playback error or mutates the viewer identity. */
internal class FallbackVideoFrames(
    private val local: VideoFrameProvider,
    private val remote: VideoFrameProvider,
    private val fallbackUri: String?,
    private val originalUri: String?,
    private val qualities: suspend (String) -> List<RemoteVideoQuality>,
) : VideoFrameProvider {
    override fun frames(uri: String): Flow<VideoFrameEvent> = flow {
        var remoteUri = uri
        if (uri.startsWith("content://")) {
            try {
                emitAll(local.frames(uri))
                return@flow
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                remoteUri = fallbackUri ?: throw failure
            }
        }
        try {
            emitAll(remote.frames(remoteUri))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: UnsupportedVideoFrames) {
            if (remoteUri != originalUri) throw failure
            val hls = qualities(remoteUri).firstOrNull { it.label == "Auto" } ?: throw failure
            emitAll(remote.frames(hls.uri))
        }
    }
}
