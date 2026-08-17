package com.syrok0010.nextgallery.data.cache

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

@Entity(tableName = "memories_cache_metadata")
data class MemoriesCacheMetadataEntity(
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

@Entity(tableName = "memories_timeline_days")
data class TimelineDayEntity(
    @PrimaryKey val dayId: Int,
    val count: Int,
    val sortOrder: Int,
)

@Entity(
    tableName = "memories_media",
    indices = [Index(value = ["dayId"])],
)
data class MemoriesMediaEntity(
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
)

data class IdentifiedMemoriesMedia(
    @Embedded val media: MemoriesMediaEntity,
    val mediaId: String,
)

enum class MediaIdentifierKind {
    MemoriesFile,
    LocalContent,
    Auid,
    Buid,
}

@Entity(
    tableName = "media_identifiers",
    primaryKeys = ["kind", "value"],
    indices = [Index(value = ["mediaId"])],
)
data class MediaIdentifierEntity(
    val kind: MediaIdentifierKind,
    val value: String,
    val mediaId: String,
)

@Entity(
    tableName = "media_identity_conflicts",
    primaryKeys = ["source", "sourceKey"],
)
data class MediaIdentityConflictEntity(
    val source: MediaSourceKind,
    val sourceKey: String,
    val auid: String?,
    val buid: String?,
    val conflictingMediaIds: String,
)

@Entity(
    tableName = "local_media_projection",
    indices = [Index(value = ["takenAtEpochSeconds"])],
)
data class LocalMediaEntity(
    @PrimaryKey val contentUri: String,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val takenAtEpochSeconds: Long,
    val modifiedAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val auid: String?,
    val buid: String?,
)

data class IdentifiedLocalMedia(
    @Embedded val media: LocalMediaEntity,
    val mediaId: String,
)

@Entity(tableName = "memories_loaded_days")
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
