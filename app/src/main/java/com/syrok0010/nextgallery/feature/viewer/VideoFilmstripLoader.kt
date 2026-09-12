package com.syrok0010.nextgallery.feature.viewer

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch

/** Loading and retry belong to the expanded card's lifetime, independently of playback. */
internal class VideoFilmstripLoader(
    private val provider: VideoFrameProvider,
    private val sourceUri: String,
    private val scope: CoroutineScope,
) {
    private val projection = VideoFilmstripProjection<Bitmap>()
    var state by mutableStateOf(projection.state)
        private set
    private var loading: Job? = null
    private var wasScrolling = false

    fun retry() {
        loading?.cancel()
        loading = scope.launch {
            projection.retry()
            state = projection.state
            try {
                provider.frames(sourceUri).collect { event ->
                    when (event) {
                        is VideoFrameEvent.Duration -> projection.durationKnown(event.millis)
                        is VideoFrameEvent.Frame -> projection.frameReady(event.index, event.bitmap)
                    }
                    state = projection.state
                }
                projection.finished()
            } catch (_: TimeoutCancellationException) {
                projection.failed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: java.io.IOException) {
                projection.failed()
            } catch (_: kotlinx.serialization.SerializationException) {
                projection.failed()
            } catch (_: RuntimeException) {
                projection.failed()
            }
            state = projection.state
        }
    }

    fun scroll(fraction: Float, isScrolling: Boolean, uptimeMillis: Long, seek: (Long, Boolean) -> Unit, finish: () -> Unit) {
        if (isScrolling || wasScrolling) {
            projection.scrub(fraction, uptimeMillis, finished = !isScrolling)?.let {
                seek(it, !isScrolling)
            }
            if (!isScrolling) finish()
        }
        wasScrolling = isScrolling
    }

    fun seek(fraction: Float, uptimeMillis: Long): Long? =
        projection.scrub(fraction, uptimeMillis, finished = true)

    fun close() { loading?.cancel() }
}
