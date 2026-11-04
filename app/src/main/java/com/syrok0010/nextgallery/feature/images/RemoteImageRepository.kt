package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class RemoteImageRepository(
    private val memories: MemoriesApiClient,
    private val cacheRepository: RemoteImageCache,
) {
    /**
     * Emits each key once its thumbnail is in the cache: cached keys first, then remote ones as
     * the server streams them. A key whose preview could not be stored is not emitted.
     */
    fun ensureThumbnails(
        credentials: AccountCredentials,
        requestedKeys: List<ThumbnailKey>,
    ): Flow<ThumbnailKey> =
        flow {
            if (requestedKeys.isEmpty()) return@flow

            val accountScope = credentials.thumbnailAccountScope()
            val firstKey = requestedKeys.first()
            require(
                requestedKeys.all { key ->
                    key.accountScope == accountScope &&
                        key.width == firstKey.width &&
                        key.height == firstKey.height
                },
            ) {
                "A thumbnail batch must belong to one account and use one size"
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
                    accountScope = accountScope,
                )
            }.getOrDefault(emptyList())
            cachedKeys.forEach { emit(it) }
            val cachedFileIds = cachedKeys.mapTo(mutableSetOf()) { it.fileId }
            val missingFileIds = distinctFileIds.filterNot { it in cachedFileIds }
            if (missingFileIds.isEmpty()) return@flow

            memories
                .multipreview(missingFileIds, firstKey.width, firstKey.height)
                .collect { preview ->
                    val stored = bestEffort {
                        cacheRepository.saveThumbnails(
                            previews = listOf(preview),
                            width = firstKey.width,
                            height = firstKey.height,
                            etagsByFileId = etagsByFileId,
                            accountScope = accountScope,
                        )
                    }.isSuccess
                    val key = keysByFileId[preview.fileId]
                    if (stored && key != null) emit(key)
                }
        }
}
