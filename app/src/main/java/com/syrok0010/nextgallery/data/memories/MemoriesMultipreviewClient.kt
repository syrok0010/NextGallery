package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MemoriesMultipreviewClient(
    private val transport: NextcloudTransport,
    private val json: Json,
) {
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
        val body = json.encodeToString(MemoriesMultipreviewRequest(files = requests))
            .toRequestBody("application/json".toMediaType())
        val request = transport.authenticatedRequestBuilder(
            credentials = credentials,
            url = "${transport.normalizeBaseUrl(credentials.serverUrl)}apps/memories/api/image/multipreview",
            accept = "application/octet-stream",
        )
            .post(body)
            .build()
        return withContext(Dispatchers.IO) {
            val response = transport.authenticatedClient(credentials).newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    error("Multipreview failed with HTTP ${it.code}")
                }

                val responseBytes = checkNotNull(it.body).bytes()
                val previewsByRequestId = parseMemoriesMultipreviewResponse(responseBytes, json)
                    .associateBy { preview -> preview.requestId }

                requests.mapNotNull { previewRequest ->
                    previewsByRequestId[previewRequest.reqid]?.copy(fileId = previewRequest.fileid)
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_THUMBNAIL_SIZE = 512
    }
}

@Serializable
private data class MemoriesMultipreviewRequest(
    val files: List<MemoriesMultipreviewFileRequest>,
)

@Serializable
private data class MemoriesMultipreviewFileRequest(
    val fileid: Long,
    val x: Int,
    val y: Int,
    val a: String,
    val reqid: Int,
)
