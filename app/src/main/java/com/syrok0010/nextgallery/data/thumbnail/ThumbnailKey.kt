package com.syrok0010.nextgallery.data.thumbnail

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
