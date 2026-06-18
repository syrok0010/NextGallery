package com.syrok0010.nextgallery.data.memories

import java.time.LocalDate

data class MemoriesConfig(
    val version: String,
    val timelinePath: String?,
    val albumsEnabled: Boolean,
    val recognizeEnabled: Boolean,
    val faceRecognitionEnabled: Boolean,
    val previewGeneratorEnabled: Boolean,
    val stackRawFiles: Boolean,
    val dedupIdentical: Boolean,
)

data class TimelineSnapshot(
    val config: MemoriesConfig,
    val totalDayCount: Int,
    val totalMediaCountHint: Int,
    val items: List<MediaItem>,
) {
    val memoriesVersion: String = config.version
    val timelinePath: String? = config.timelinePath
}

data class MediaItem(
    val fileId: Long,
    val dayId: Int,
    val day: LocalDate,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val etag: String?,
    val livePhotoId: String?,
    val auid: String?,
    val buid: String?,
    val sharedBy: String?,
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val thumbnailUrl: String,
    val detailPreviewUrl: String,
)
