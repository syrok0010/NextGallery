package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesMultipreviewFileRequest
import com.syrok0010.nextgallery.core.network.memories.MemoriesMultipreviewRequest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.mapNotNull

class MemoriesMultipreviewClient(private val memories: MemoriesApiClient) {
    /** Emits previews as the server streams them; files without a preview are skipped. */
    fun loadThumbnails(
        fileIds: List<Long>,
        width: Int = DEFAULT_THUMBNAIL_SIZE,
        height: Int = DEFAULT_THUMBNAIL_SIZE,
    ): Flow<ThumbnailPreview> {
        if (fileIds.isEmpty()) return emptyFlow()
        val distinct = fileIds.distinct()
        val requests = distinct.mapIndexed { index, fileId ->
            MemoriesMultipreviewFileRequest(
                fileid = fileId,
                x = width,
                y = height,
                a = "1",
                reqid = index + 1,
            )
        }
        return memories
            .multipreview(MemoriesMultipreviewRequest(files = requests))
            .mapNotNull { preview ->
                distinct.getOrNull(preview.reqid - 1)?.let { fileId ->
                    ThumbnailPreview(fileId, preview.type, preview.bytes)
                }
            }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIZE = 512
    }
}
