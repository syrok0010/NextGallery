package com.syrok0010.nextgallery.data.thumbnail

import android.content.Context
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.memory.MemoryCache
import coil3.request.Options
import coil3.video.VideoFrameDecoder
import com.syrok0010.nextgallery.data.cache.ThumbnailFileStore
import java.io.File
import okio.Path.Companion.toPath

internal fun createNextGalleryImageLoader(
    context: Context,
    thumbnailBatchLoader: ThumbnailBatchLoader,
    thumbnailFileStore: ThumbnailFileStore,
): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            add(ThumbnailRequestKeyer)
            add(ThumbnailFetcher.Factory(thumbnailBatchLoader, thumbnailFileStore))
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, THUMBNAIL_MEMORY_CACHE_PERCENT)
                .build()
        }
        .build()
}

internal object ThumbnailRequestKeyer : Keyer<ThumbnailRequest> {
    override fun key(data: ThumbnailRequest, options: Options): String {
        return data.key.coilMemoryCacheKey()
    }
}

private class ThumbnailFetcher(
    private val data: ThumbnailRequest,
    private val options: Options,
    private val thumbnailBatchLoader: ThumbnailBatchLoader,
    private val thumbnailFileStore: ThumbnailFileStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = resolveThumbnailFile(
            request = data,
            thumbnailFile = thumbnailFileStore::fileFor,
            ensureAvailable = thumbnailBatchLoader::ensureAvailable,
        ) ?: return null

        return SourceFetchResult(
            source = ImageSource(
                file = file.absolutePath.toPath(),
                fileSystem = options.fileSystem,
            ),
            mimeType = null,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(
        private val thumbnailBatchLoader: ThumbnailBatchLoader,
        private val thumbnailFileStore: ThumbnailFileStore,
    ) : Fetcher.Factory<ThumbnailRequest> {
        override fun create(
            data: ThumbnailRequest,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ThumbnailFetcher(
                data = data,
                options = options,
                thumbnailBatchLoader = thumbnailBatchLoader,
                thumbnailFileStore = thumbnailFileStore,
            )
        }
    }
}

internal suspend fun resolveThumbnailFile(
    request: ThumbnailRequest,
    thumbnailFile: (ThumbnailKey) -> File,
    ensureAvailable: suspend (ThumbnailRequest) -> Boolean,
): File? {
    val file = thumbnailFile(request.key)
    if (file.isFile) {
        return file
    }
    if (!ensureAvailable(request)) {
        return null
    }
    return file.takeIf(File::isFile)
}

private const val THUMBNAIL_MEMORY_CACHE_PERCENT = 0.15
