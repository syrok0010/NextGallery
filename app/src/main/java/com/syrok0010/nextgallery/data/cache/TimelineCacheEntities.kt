package com.syrok0010.nextgallery.data.cache

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "cache_metadata")
data class CacheMetadataEntity(
    @PrimaryKey val id: Int = CACHE_METADATA_ID,
    val serverUrl: String,
    val memoriesVersion: String,
    val timelinePath: String?,
    val albumsEnabled: Boolean,
    val recognizeEnabled: Boolean,
    val faceRecognitionEnabled: Boolean,
    val previewGeneratorEnabled: Boolean,
    val stackRawFiles: Boolean,
    val dedupIdentical: Boolean,
    val cachedAtEpochMillis: Long,
)

@Entity(tableName = "timeline_days")
data class TimelineDayEntity(
    @PrimaryKey val dayId: Int,
    val count: Int,
    val sortOrder: Int,
)

@Entity(
    tableName = "media_items",
    indices = [Index(value = ["dayId"])],
)
data class MediaItemEntity(
    @PrimaryKey val fileId: Long,
    val dayId: Int,
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

@Entity(tableName = "loaded_days")
data class LoadedDayEntity(
    @PrimaryKey val dayId: Int,
    val loadedAtEpochMillis: Long,
)

@Entity(
    tableName = "thumbnail_cache",
    primaryKeys = ["fileId", "width", "height"],
)
data class ThumbnailCacheEntity(
    val fileId: Long,
    val width: Int,
    val height: Int,
    val mimeType: String,
    val cacheKey: String,
    val relativePath: String,
    val cachedAtEpochMillis: Long,
)

data class TimelineDayCount(
    val dayId: Int,
    val count: Int,
)

const val CACHE_METADATA_ID = 1
