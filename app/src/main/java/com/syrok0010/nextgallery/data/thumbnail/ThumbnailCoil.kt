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
import com.syrok0010.nextgallery.data.cache.ThumbnailFileStore
import okio.Path.Companion.toPath

internal fun createNextGalleryImageLoader(
    context: Context,
    thumbnailFileStore: ThumbnailFileStore,
): ImageLoader {
    return ImageLoader.Builder(context)
        .components {
            add(ThumbnailKeyer)
            add(ThumbnailFetcher.Factory(thumbnailFileStore))
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, THUMBNAIL_MEMORY_CACHE_PERCENT)
                .build()
        }
        .build()
}

internal object ThumbnailKeyer : Keyer<ThumbnailKey> {
    override fun key(data: ThumbnailKey, options: Options): String {
        return data.coilMemoryCacheKey()
    }
}

private class ThumbnailFetcher(
    private val data: ThumbnailKey,
    private val options: Options,
    private val thumbnailFileStore: ThumbnailFileStore,
) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val file = thumbnailFileStore.fileFor(data)
        if (!file.isFile) {
            return null
        }

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
        private val thumbnailFileStore: ThumbnailFileStore,
    ) : Fetcher.Factory<ThumbnailKey> {
        override fun create(
            data: ThumbnailKey,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return ThumbnailFetcher(
                data = data,
                options = options,
                thumbnailFileStore = thumbnailFileStore,
            )
        }
    }
}

private const val THUMBNAIL_MEMORY_CACHE_PERCENT = 0.15
