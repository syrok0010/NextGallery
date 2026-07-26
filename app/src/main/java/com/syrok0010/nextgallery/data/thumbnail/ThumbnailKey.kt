package com.syrok0010.nextgallery.data.thumbnail

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.network.NextcloudTransport

data class ThumbnailKey(
    val accountScope: String,
    val fileId: Long,
    val width: Int,
    val height: Int,
    val etag: String?,
) {
    internal fun coilMemoryCacheKey(): String {
        return buildString {
            append("nextgallery-thumbnail:")
            append(accountScope)
            append(':')
            append(fileId)
            append(':')
            append(width)
            append('x')
            append(height)
            append(':')
            append(etag.orEmpty())
        }
    }
}

data class ThumbnailRequest(
    val key: ThumbnailKey,
    val credentials: AccountCredentials,
)

fun thumbnailRequest(
    credentials: AccountCredentials,
    fileId: Long,
    etag: String?,
    width: Int = DEFAULT_THUMBNAIL_SIZE,
    height: Int = DEFAULT_THUMBNAIL_SIZE,
): ThumbnailRequest {
    return ThumbnailRequest(
        key = ThumbnailKey(
            accountScope = credentials.thumbnailAccountScope(),
            fileId = fileId,
            width = width,
            height = height,
            etag = etag,
        ),
        credentials = credentials,
    )
}

internal fun AccountCredentials.thumbnailAccountScope(): String {
    return "${NextcloudTransport.normalizeServerOrigin(serverUrl)}|$loginName"
}

const val DEFAULT_THUMBNAIL_SIZE = 512
