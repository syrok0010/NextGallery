package com.syrok0010.nextgallery.data.memories

private const val PREVIEW_AUTH_QUERY = "a=1"

data class MemoriesImageUrlSet(
    val thumbnailUrl: String,
    val detailPreviewUrl: String,
    val originalUrl: String,
)

object MemoriesAssetUrlFactory {
    fun urlsFor(
        assetRef: MediaAssetRef,
        serverUrl: String,
    ): MemoriesImageUrlSet {
        val normalizedServerUrl = serverUrl.trimEnd('/')
        return when (assetRef) {
            is MediaAssetRef.MemoriesFile -> {
                val fileId = assetRef.photoFileId
                MemoriesImageUrlSet(
                    thumbnailUrl = buildPreviewUrl(normalizedServerUrl, fileId, 512),
                    detailPreviewUrl = buildPreviewUrl(normalizedServerUrl, fileId, 1600),
                    originalUrl = "$normalizedServerUrl/apps/memories/api/stream/$fileId",
                )
            }
        }
    }

    private fun buildPreviewUrl(
        normalizedServerUrl: String,
        fileId: Long,
        size: Int,
    ): String {
        return "$normalizedServerUrl/apps/memories/api/image/preview/$fileId?x=$size&y=$size&$PREVIEW_AUTH_QUERY"
    }
}
