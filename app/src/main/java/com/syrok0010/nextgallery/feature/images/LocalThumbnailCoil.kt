package com.syrok0010.nextgallery.feature.images

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
import coil3.size.Size as CoilSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

/** The local thumbnail path is deliberately separate from the original content URI path. */
internal data class LocalThumbnailRequest(
    val contentUri: String,
    val cacheKey: String,
)

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
                options.context.contentResolver.loadThumbnail(
                    data.contentUri.toUri(),
                    localThumbnailTargetSize(options.size),
                    cancellationSignal,
                )
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

internal fun localThumbnailTargetSize(size: CoilSize): Size {
    return Size(
        size.width.pixelsOrDefault(DEFAULT_LOCAL_THUMBNAIL_SIZE),
        size.height.pixelsOrDefault(DEFAULT_LOCAL_THUMBNAIL_SIZE),
    )
}

private fun Dimension.pixelsOrDefault(default: Int): Int =
    (this as? Dimension.Pixels)?.px?.coerceAtLeast(1) ?: default

private const val DEFAULT_LOCAL_THUMBNAIL_SIZE = 512
