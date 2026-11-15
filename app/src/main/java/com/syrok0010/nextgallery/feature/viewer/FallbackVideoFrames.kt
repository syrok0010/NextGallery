package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.feature.viewer.playback.RemoteVideoQuality
import com.syrok0010.nextgallery.feature.viewer.playback.lowestForFrames
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.SerializationException

/** A frame failure never dispatches a playback error or mutates the viewer identity. */
internal class FallbackVideoFrames(
    private val local: VideoFrameProvider,
    private val remote: VideoFrameProvider,
    private val fallbackUri: String?,
    private val qualities: suspend (String) -> List<RemoteVideoQuality>,
) : VideoFrameProvider {
    override fun frames(uri: String): Flow<VideoFrameEvent> =
        flow {
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
            val preferred = try {
                qualities(remoteUri).lowestForFrames()?.uri ?: remoteUri
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: IOException) {
                remoteUri
            } catch (_: SerializationException) {
                remoteUri
            }
            try {
                emitAll(remote.frames(preferred))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: IOException) {
                if (preferred == remoteUri) throw failure
                emitAll(remote.frames(remoteUri))
            }
        }
}
