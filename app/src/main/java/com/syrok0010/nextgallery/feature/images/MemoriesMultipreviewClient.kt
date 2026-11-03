package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.network.memories.MemoriesApiClient
import com.syrok0010.nextgallery.core.network.memories.MemoriesMultipreviewFileRequest
import com.syrok0010.nextgallery.core.network.memories.MemoriesMultipreviewRequest
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MemoriesMultipreviewClient(private val memories: MemoriesApiClient, private val json: Json) {
    internal constructor(
        transport: NextcloudTransport,
        json: Json,
    ) : this(MemoriesApiClient(transport), json)

    suspend fun loadThumbnails(
        credentials: AccountCredentials,
        fileIds: List<Long>,
        width: Int = DEFAULT_THUMBNAIL_SIZE,
        height: Int = DEFAULT_THUMBNAIL_SIZE,
    ): List<ThumbnailPreview> {
        if (fileIds.isEmpty()) {
            return emptyList()
        }

        val requests = fileIds.distinct().mapIndexed { index, fileId ->
            MemoriesMultipreviewFileRequest(
                fileid = fileId,
                x = width,
                y = height,
                a = "1",
                reqid = index + 1,
            )
        }
        return withContext(Dispatchers.IO) {
            val responseBytes = memories.multipreview(
                credentials,
                MemoriesMultipreviewRequest(files = requests),
            )
            val previewsByRequestId = parseMemoriesMultipreviewResponse(responseBytes, json)
                .associateBy { preview -> preview.requestId }

            requests.mapNotNull { previewRequest ->
                previewsByRequestId[previewRequest.reqid]?.copy(fileId = previewRequest.fileid)
            }
        }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIZE = 512
    }
}
