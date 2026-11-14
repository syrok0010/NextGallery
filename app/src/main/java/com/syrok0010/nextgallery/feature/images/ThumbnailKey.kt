package com.syrok0010.nextgallery.feature.images

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.session.AccountCredentials

/** One cached preview. The cache holds one account only: logout erases it (ADR 0010). */
data class ThumbnailKey(val fileId: Long, val width: Int, val height: Int, val etag: String?) {
    internal fun coilMemoryCacheKey(): String =
        buildString {
            append("nextgallery-thumbnail:")
            append(fileId)
            append(':')
            append(width)
            append('x')
            append(height)
            append(':')
            append(etag.orEmpty())
        }
}

data class ThumbnailRequest(val key: ThumbnailKey, val credentials: AccountCredentials)

fun thumbnailRequest(
    credentials: AccountCredentials,
    fileId: Long,
    etag: String?,
    width: Int = DEFAULT_THUMBNAIL_SIZE,
    height: Int = DEFAULT_THUMBNAIL_SIZE,
): ThumbnailRequest =
    ThumbnailRequest(
        key = ThumbnailKey(
            fileId = fileId,
            width = width,
            height = height,
            etag = etag,
        ),
        credentials = credentials,
    )

const val DEFAULT_THUMBNAIL_SIZE = 512

internal fun MediaAssetRef.LocalContent.coilCacheKey(): String =
    buildString {
        append("nextgallery-local-media:")
        append(contentUri)
        append(':')
        append(modifiedAtEpochSeconds ?: "unknown")
    }
