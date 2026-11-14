package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesPreviewDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class RemoteImageRepository(
    private val memories: MemoriesApiClient,
    private val cacheRepository: RemoteImageCache,
) {
    /**
     * Emits each key once its thumbnail is in the cache: cached keys first, then remote ones as
     * the server streams them, then the ones multipreview skipped. A key whose preview could not
     * be loaded or stored is not emitted.
     */
    fun ensureThumbnails(
        credentials: AccountCredentials,
        requestedKeys: List<ThumbnailKey>,
    ): Flow<ThumbnailKey> =
        flow {
            if (requestedKeys.isEmpty()) return@flow

            val firstKey = requestedKeys.first()
            require(
                requestedKeys.all { it.width == firstKey.width && it.height == firstKey.height },
            ) {
                "A thumbnail batch must use one size"
            }
            val keysByFileId = requestedKeys.distinctBy { it.fileId }.associateBy { it.fileId }
            val distinctFileIds = keysByFileId.keys.toList()
            val etagsByFileId = keysByFileId.mapValues { (_, key) -> key.etag }
            val cachedKeys = bestEffort {
                cacheRepository.loadThumbnailKeys(
                    fileIds = distinctFileIds,
                    width = firstKey.width,
                    height = firstKey.height,
                    etagsByFileId = etagsByFileId,
                )
            }.getOrDefault(emptyList())
            cachedKeys.forEach { emit(it) }
            val cachedFileIds = cachedKeys.mapTo(mutableSetOf()) { it.fileId }
            val missingFileIds = distinctFileIds.filterNot { it in cachedFileIds }
            if (missingFileIds.isEmpty()) return@flow

            suspend fun store(preview: MemoriesPreviewDto): Boolean =
                bestEffort {
                    cacheRepository.saveThumbnails(
                        previews = listOf(preview),
                        width = firstKey.width,
                        height = firstKey.height,
                        etagsByFileId = etagsByFileId,
                    )
                }.isSuccess

            val receivedFileIds = mutableSetOf<Long>()
            memories
                .multipreview(missingFileIds, firstKey.width, firstKey.height)
                .collect { preview ->
                    receivedFileIds += preview.fileId
                    val key = keysByFileId[preview.fileId]
                    if (store(preview) && key != null) emit(key)
                }
            // Multipreview skips files whose preview the server has not generated yet, e.g. just
            // uploaded ones; the single preview request generates it.
            missingFileIds.filterNot { it in receivedFileIds }.forEach { fileId ->
                val preview = bestEffort {
                    memories.preview(fileId, firstKey.width, firstKey.height)
                }.getOrNull()
                if (preview != null && store(preview)) emit(keysByFileId.getValue(fileId))
            }
        }
}
