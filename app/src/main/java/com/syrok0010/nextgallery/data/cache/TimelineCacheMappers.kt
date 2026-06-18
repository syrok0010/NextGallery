package com.syrok0010.nextgallery.data.cache

import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import java.time.LocalDate

fun MemoriesConfig.toCacheMetadataEntity(
    serverUrl: String,
    cachedAtEpochMillis: Long,
): CacheMetadataEntity {
    return CacheMetadataEntity(
        serverUrl = serverUrl,
        memoriesVersion = version,
        timelinePath = timelinePath,
        albumsEnabled = albumsEnabled,
        recognizeEnabled = recognizeEnabled,
        faceRecognitionEnabled = faceRecognitionEnabled,
        previewGeneratorEnabled = previewGeneratorEnabled,
        stackRawFiles = stackRawFiles,
        dedupIdentical = dedupIdentical,
        cachedAtEpochMillis = cachedAtEpochMillis,
    )
}

fun CacheMetadataEntity.toMemoriesConfig(): MemoriesConfig {
    return MemoriesConfig(
        version = memoriesVersion,
        timelinePath = timelinePath,
        albumsEnabled = albumsEnabled,
        recognizeEnabled = recognizeEnabled,
        faceRecognitionEnabled = faceRecognitionEnabled,
        previewGeneratorEnabled = previewGeneratorEnabled,
        stackRawFiles = stackRawFiles,
        dedupIdentical = dedupIdentical,
    )
}

fun TimelineDay.toEntity(sortOrder: Int): TimelineDayEntity {
    return TimelineDayEntity(
        dayId = dayId,
        count = count,
        sortOrder = sortOrder,
    )
}

fun TimelineDayEntity.toTimelineDay(): TimelineDay {
    return TimelineDay(
        dayId = dayId,
        count = count,
    )
}

fun MediaItem.toEntity(): MediaItemEntity {
    return MediaItemEntity(
        fileId = fileId,
        dayId = dayId,
        displayName = displayName,
        mimeType = mimeType,
        width = width,
        height = height,
        etag = etag,
        livePhotoId = livePhotoId,
        auid = auid,
        buid = buid,
        sharedBy = sharedBy,
        takenAtEpochSeconds = takenAtEpochSeconds,
        isVideo = isVideo,
        videoDurationSeconds = videoDurationSeconds,
        isFavorite = isFavorite,
        isHidden = isHidden,
        thumbnailUrl = thumbnailUrl,
        detailPreviewUrl = detailPreviewUrl,
    )
}

fun MediaItemEntity.toMediaItem(): MediaItem {
    return MediaItem(
        fileId = fileId,
        dayId = dayId,
        day = LocalDate.ofEpochDay(dayId.toLong()),
        displayName = displayName,
        mimeType = mimeType,
        width = width,
        height = height,
        etag = etag,
        livePhotoId = livePhotoId,
        auid = auid,
        buid = buid,
        sharedBy = sharedBy,
        takenAtEpochSeconds = takenAtEpochSeconds,
        isVideo = isVideo,
        videoDurationSeconds = videoDurationSeconds,
        isFavorite = isFavorite,
        isHidden = isHidden,
        thumbnailUrl = thumbnailUrl,
        detailPreviewUrl = detailPreviewUrl,
    )
}
