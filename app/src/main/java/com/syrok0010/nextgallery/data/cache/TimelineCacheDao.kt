package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface TimelineCacheDao {
    @Query("SELECT * FROM cache_metadata WHERE id = :id")
    suspend fun metadata(id: Int = CACHE_METADATA_ID): CacheMetadataEntity?

    @Query("SELECT * FROM timeline_days ORDER BY sortOrder ASC")
    suspend fun timelineDays(): List<TimelineDayEntity>

    @Query("SELECT dayId, count FROM timeline_days")
    suspend fun timelineDayCounts(): List<TimelineDayCount>

    @Query("SELECT * FROM media_items ORDER BY takenAtEpochSeconds DESC, fileId DESC")
    suspend fun mediaItems(): List<MediaItemEntity>

    @Query("SELECT dayId FROM loaded_days")
    suspend fun loadedDayIds(): List<Int>

    @Query("SELECT fileId FROM media_items WHERE dayId IN (:dayIds)")
    suspend fun fileIdsForDays(dayIds: Collection<Int>): List<Long>

    @Query("SELECT * FROM thumbnail_cache WHERE fileId IN (:fileIds)")
    suspend fun thumbnailRowsForFileIds(fileIds: Collection<Long>): List<ThumbnailCacheEntity>

    @Query(
        """
        SELECT * FROM thumbnail_cache
        WHERE fileId IN (:fileIds) AND width = :width AND height = :height
        """
    )
    suspend fun thumbnailRows(
        fileIds: Collection<Long>,
        width: Int,
        height: Int,
    ): List<ThumbnailCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: CacheMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTimelineDays(entities: List<TimelineDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItems(entities: List<MediaItemEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoadedDays(entities: List<LoadedDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThumbnailRows(entities: List<ThumbnailCacheEntity>)

    @Query("DELETE FROM timeline_days")
    suspend fun deleteTimelineDays()

    @Query("DELETE FROM media_items WHERE dayId IN (:dayIds)")
    suspend fun deleteMediaItemsForDays(dayIds: Collection<Int>)

    @Query("DELETE FROM loaded_days WHERE dayId IN (:dayIds)")
    suspend fun deleteLoadedDays(dayIds: Collection<Int>)

    @Query("DELETE FROM thumbnail_cache WHERE fileId IN (:fileIds)")
    suspend fun deleteThumbnailRowsForFileIds(fileIds: Collection<Long>)

    @Query(
        """
        DELETE FROM thumbnail_cache
        WHERE fileId IN (:fileIds) AND width = :width AND height = :height
        """
    )
    suspend fun deleteThumbnailRows(
        fileIds: Collection<Long>,
        width: Int,
        height: Int,
    )

    @Query("DELETE FROM cache_metadata")
    suspend fun deleteMetadata()

    @Query("DELETE FROM timeline_days")
    suspend fun deleteAllTimelineDays()

    @Query("DELETE FROM media_items")
    suspend fun deleteAllMediaItems()

    @Query("DELETE FROM loaded_days")
    suspend fun deleteAllLoadedDays()

    @Query("DELETE FROM thumbnail_cache")
    suspend fun deleteAllThumbnailRows()
}
