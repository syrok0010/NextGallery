package com.syrok0010.nextgallery.data.cache

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionItem
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate

fun MemoriesConfig.toCacheMetadataEntity(
    serverUrl: String,
    cachedAtEpochMillis: Long,
): MemoriesCacheMetadataEntity {
    return MemoriesCacheMetadataEntity(
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

fun MemoriesCacheMetadataEntity.toMemoriesConfig(): MemoriesConfig {
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

fun MediaItem.toMemoriesMediaEntity(): MemoriesMediaEntity {
    val mediaAssetRef = assetRef as? MediaAssetRef.MemoriesFile
        ?: error("Local items are not stored in the remote timeline cache")
    return MemoriesMediaEntity(
        fileId = mediaAssetRef.photoFileId,
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
    )
}

fun IdentifiedMemoriesMedia.toMediaItem(): MediaItem {
    return MediaItem(
        mediaId = MediaId(mediaId),
        remoteFileId = media.fileId,
        dayId = media.dayId,
        day = LocalDate.ofEpochDay(media.dayId.toLong()),
        displayName = media.displayName,
        mimeType = media.mimeType,
        width = media.width,
        height = media.height,
        etag = media.etag,
        livePhotoId = media.livePhotoId,
        auid = media.auid,
        buid = media.buid,
        sharedBy = media.sharedBy,
        takenAtEpochSeconds = media.takenAtEpochSeconds,
        isVideo = media.isVideo,
        videoDurationSeconds = media.videoDurationSeconds,
        isFavorite = media.isFavorite,
        isHidden = media.isHidden,
        assetRef = MediaAssetRef.MemoriesFile(photoFileId = media.fileId),
    )
}

fun MediaItem.toLocalMediaEntity(): LocalMediaEntity {
    val localContent = assetRef as? MediaAssetRef.LocalContent
        ?: error("Only local media can be stored in the local projection")
    return LocalMediaEntity(
        contentUri = localContent.contentUri,
        displayName = displayName,
        mimeType = mimeType,
        width = width,
        height = height,
        takenAtEpochSeconds = requireNotNull(takenAtEpochSeconds),
        modifiedAtEpochSeconds = localContent.modifiedAtEpochSeconds,
        isVideo = isVideo,
        videoDurationSeconds = videoDurationSeconds,
        auid = auid,
        buid = buid,
    )
}

fun IdentifiedLocalMedia.toMediaItem(): MediaItem = LocalMediaProjectionItem(
    mediaId = MediaId(mediaId),
    contentUri = media.contentUri,
    displayName = media.displayName,
    mimeType = media.mimeType,
    width = media.width,
    height = media.height,
    takenAtEpochSeconds = media.takenAtEpochSeconds,
    modifiedAtEpochSeconds = media.modifiedAtEpochSeconds,
    isVideo = media.isVideo,
    videoDurationSeconds = media.videoDurationSeconds,
    auid = media.auid,
    buid = media.buid,
).toMediaItem()
