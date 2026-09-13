package com.syrok0010.nextgallery.feature.viewer

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.inspector.MetadataRetriever
import androidx.media3.inspector.frame.FrameExtractor
import com.google.common.util.concurrent.ListenableFuture
import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Independent of the playback player, with the same authenticated original/HLS transport. */
@OptIn(UnstableApi::class)
internal class RemoteVideoFrames(
    private val context: Context,
    private val sourceFactory: () -> MediaSource.Factory,
) : VideoFrameProvider {
    override fun frames(uri: String): Flow<VideoFrameEvent> = flow {
        decoder.withLock {
            withTimeout(45_000) {
                val item = MediaItem.fromUri(uri)
                val duration = MetadataRetriever.Builder(context, item)
                    .setMediaSourceFactory(sourceFactory()).build().use { metadata ->
                        withTimeout(8_000) { metadata.retrieveDurationUs().awaitFrameResult() / 1000 }
                    }
                val plan = VideoFilmstripProjection<Unit>().apply { durationKnown(duration) }.state.positionsMillis
                if (plan.isEmpty()) throw IOException("Video duration unavailable")
                emit(VideoFrameEvent.Duration(duration))
                FrameExtractor.Builder(context, item)
                    .setMediaSourceFactory(sourceFactory())
                    .setEffects(listOf(Presentation.createForWidthAndHeight(160, 160, Presentation.LAYOUT_SCALE_TO_FIT)))
                    .build().use { extractor ->
                        plan.forEachIndexed { index, position ->
                            val frame = withTimeout(5_000) { extractor.getFrame(position).awaitFrameResult() }
                            emit(VideoFrameEvent.Frame(index, frame.bitmap))
                        }
                    }
            }
        }
    }.catch { failure ->
        when (failure) {
            is TimeoutCancellationException -> throw IOException("Frame extraction timed out", failure)
            is CancellationException -> throw failure
            is PlaybackException -> {
                if (failure.toVideoPlaybackError() == VideoPlaybackError.CannotPlay) {
                    throw UnsupportedVideoFrames(failure)
                }
                throw IOException("Remote frames unavailable", failure)
            }
            is androidx.media3.common.ParserException -> throw UnsupportedVideoFrames(failure)
            else -> throw IOException("Frame extraction unavailable", failure)
        }
    }

    companion object { private val decoder = Mutex() }
}

private suspend fun <T> ListenableFuture<T>.awaitFrameResult(): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel(true) }
    addListener({
        try {
            continuation.resume(get())
        } catch (error: ExecutionException) {
            continuation.resumeWithException(error.cause ?: error)
        } catch (error: Exception) {
            continuation.resumeWithException(error)
        }
    }, Executor { it.run() })
}

internal class UnsupportedVideoFrames(cause: Throwable) : IOException("Video format unavailable for frame extraction", cause)
