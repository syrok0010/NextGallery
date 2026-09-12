package com.syrok0010.nextgallery.feature.viewer

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import androidx.core.net.toUri

internal sealed interface VideoFrameEvent {
    data class Duration(val millis: Long) : VideoFrameEvent
    data class Frame(val index: Int, val bitmap: Bitmap) : VideoFrameEvent
}

internal fun interface VideoFrameProvider {
    fun frames(uri: String): Flow<VideoFrameEvent>
}

/** One small bitmap at a time; never decode a full-resolution frame into the strip. */
internal class LocalVideoFrames(private val context: Context) : VideoFrameProvider {
    override fun frames(uri: String): Flow<VideoFrameEvent> = flow {
        decoder.withLock {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri.toUri())
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: error("Video duration unavailable")
                val plan = VideoFilmstripProjection<Bitmap>().apply { durationKnown(duration) }.state.positionsMillis
                check(plan.isNotEmpty()) { "Video duration unavailable" }
                emit(VideoFrameEvent.Duration(duration))
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 160
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 160
                val scale = 160.0 / maxOf(width, height, 1)
                plan.forEachIndexed { index, position ->
                    currentCoroutineContext().ensureActive()
                    val bitmap = retriever.getScaledFrameAtTime(
                        position * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                        (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1),
                    ) ?: error("Video frame unavailable")
                    emit(VideoFrameEvent.Frame(index, bitmap))
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    companion object { private val decoder = Mutex() }
}
