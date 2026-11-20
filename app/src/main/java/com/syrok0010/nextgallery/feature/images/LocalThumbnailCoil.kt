package com.syrok0010.nextgallery.feature.images

import android.graphics.ImageDecoder
import android.graphics.Rect
import android.os.CancellationSignal
import android.util.Size
import androidx.core.net.toUri
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.request.Options
import coil3.size.Dimension
import coil3.size.Scale
import coil3.size.Size as CoilSize
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The local thumbnail path is deliberately separate from the original content URI path. */
internal data class LocalThumbnailRequest(val contentUri: String, val cacheKey: String)

internal class LocalThumbnailFetcher(
    private val data: LocalThumbnailRequest,
    private val options: Options,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val bitmap = withContext(Dispatchers.IO) {
            val cancellationSignal = CancellationSignal()
            val cancellationHandle = coroutineContext[Job]
                ?.invokeOnCompletion { cancellationSignal.cancel() }
            try {
                val resolver = options.context.contentResolver
                val uri = data.contentUri.toUri()
                val target = localThumbnailTargetSize(options.size)
                val thumbnail = resolver.loadThumbnail(
                    uri,
                    target,
                    cancellationSignal,
                )
                if (options.scale != Scale.FILL ||
                    (thumbnail.width >= target.width && thumbnail.height >= target.height) ||
                    resolver.getType(uri)?.startsWith("image/") != true
                ) {
                    thumbnail
                } else {
                    // A fit thumbnail can leave only a few pixels across a long screenshot.
                    // ImageDecoder crops after scaling and allocates only the requested region.
                    try {
                        coroutineContext.ensureActive()
                        ImageDecoder.decodeBitmap(
                            ImageDecoder.createSource(resolver, uri),
                        ) { decoder, info, _ ->
                            val scale = maxOf(
                                target.width.toDouble() / info.size.width,
                                target.height.toDouble() / info.size.height,
                            ).coerceAtMost(1.0)
                            val width = ceil(info.size.width * scale).toInt()
                            val height = ceil(info.size.height * scale).toInt()
                            val cropWidth = target.width.coerceAtMost(width)
                            val cropHeight = target.height.coerceAtMost(height)
                            val left = (width - cropWidth) / 2
                            val top = (height - cropHeight) / 2
                            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                            decoder.setTargetSize(width, height)
                            decoder.crop = Rect(left, top, left + cropWidth, top + cropHeight)
                        }
                    } finally {
                        thumbnail.recycle()
                    }
                }
            } finally {
                cancellationHandle?.dispose()
                cancellationSignal.cancel()
            }
        }

        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    internal class Factory : Fetcher.Factory<LocalThumbnailRequest> {
        override fun create(
            data: LocalThumbnailRequest,
            options: Options,
            imageLoader: coil3.ImageLoader,
        ): Fetcher = LocalThumbnailFetcher(data, options)
    }
}

internal fun localThumbnailTargetSize(size: CoilSize): Size =
    Size(
        size.width.pixelsOrDefault(DEFAULT_LOCAL_THUMBNAIL_SIZE),
        size.height.pixelsOrDefault(DEFAULT_LOCAL_THUMBNAIL_SIZE),
    )

private fun Dimension.pixelsOrDefault(default: Int): Int =
    (this as? Dimension.Pixels)?.px?.coerceAtLeast(1) ?: default

private const val DEFAULT_LOCAL_THUMBNAIL_SIZE = 512
