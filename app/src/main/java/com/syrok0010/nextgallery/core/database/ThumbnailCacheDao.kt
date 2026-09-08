package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ThumbnailCacheDao {
    @Query("SELECT * FROM thumbnail_cache WHERE fileId IN (:fileIds)")
    suspend fun rowsForFileIds(fileIds: Collection<Long>): List<ThumbnailCacheEntity>

    @Query(
        """
        SELECT * FROM thumbnail_cache
        WHERE fileId IN (:fileIds) AND width = :width AND height = :height
        """,
    )
    suspend fun rows(
        fileIds: Collection<Long>,
        width: Int,
        height: Int,
    ): List<ThumbnailCacheEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<ThumbnailCacheEntity>)

    @Query("DELETE FROM thumbnail_cache WHERE fileId IN (:fileIds)")
    suspend fun deleteForFileIds(fileIds: Collection<Long>)

    @Query(
        """
        DELETE FROM thumbnail_cache
        WHERE fileId IN (:fileIds) AND width = :width AND height = :height
        """,
    )
    suspend fun delete(
        fileIds: Collection<Long>,
        width: Int,
        height: Int,
    )

    @Query("DELETE FROM thumbnail_cache")
    suspend fun deleteAll()
}
