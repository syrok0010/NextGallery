package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.network.bestEffort
import com.syrok0010.nextgallery.core.session.AccountCredentials

internal class RemoteImageRepository(
    private val multipreviewClient: MemoriesMultipreviewClient,
    private val cacheRepository: RemoteImageCache,
) {
    suspend fun ensureThumbnails(
        credentials: AccountCredentials,
        requestedKeys: List<ThumbnailKey>,
    ): List<ThumbnailKey> {
        if (requestedKeys.isEmpty()) {
            return emptyList()
        }

        val accountScope = credentials.thumbnailAccountScope()
        val firstKey = requestedKeys.first()
        require(requestedKeys.all { key ->
            key.accountScope == accountScope &&
                key.width == firstKey.width &&
                key.height == firstKey.height
        }) {
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
        val cachedFileIds = cachedKeys.mapTo(mutableSetOf()) { it.fileId }
        val missingFileIds = distinctFileIds.filterNot { it in cachedFileIds }
        if (missingFileIds.isEmpty()) {
            return cachedKeys
        }

        val remotePreviews = multipreviewClient.loadThumbnails(
            credentials = credentials,
            fileIds = missingFileIds,
            width = firstKey.width,
            height = firstKey.height,
        )
        val storedRemoteKeys = bestEffort {
            cacheRepository.saveThumbnails(
                previews = remotePreviews,
                width = firstKey.width,
                height = firstKey.height,
                etagsByFileId = etagsByFileId,
                accountScope = accountScope,
            )
            remotePreviews.mapNotNull { preview -> keysByFileId[preview.fileId] }
        }.getOrDefault(emptyList())

        return cachedKeys + storedRemoteKeys
    }

}
