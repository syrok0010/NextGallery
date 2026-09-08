package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoriesTimelineDao {
    @Query("SELECT * FROM memories_cache_metadata WHERE id = :id")
    suspend fun metadata(id: Int = CACHE_METADATA_ID): MemoriesCacheMetadataEntity?

    @Query("SELECT * FROM memories_timeline_days ORDER BY sortOrder ASC")
    suspend fun timelineDays(): List<TimelineDayEntity>

    @Query("SELECT dayId, count FROM memories_timeline_days")
    suspend fun timelineDayCounts(): List<TimelineDayCount>

    @Query(
        """
        SELECT media.*, identifier.mediaId
        FROM memories_media AS media
        INNER JOIN media_identifiers AS identifier
            ON identifier.kind = :identifierKind
            AND identifier.value = CAST(media.fileId AS TEXT)
        ORDER BY media.takenAtEpochSeconds DESC, media.fileId DESC
        """,
    )
    suspend fun mediaItems(
        identifierKind: MediaIdentifierKind = MediaIdentifierKind.MemoriesFile,
    ): List<IdentifiedMemoriesMedia>

    @Query("SELECT dayId FROM memories_loaded_days")
    suspend fun loadedDayIds(): List<Int>

    @Query("SELECT fileId FROM memories_media WHERE dayId IN (:dayIds)")
    suspend fun fileIdsForDays(dayIds: Collection<Int>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMetadata(entity: MemoriesCacheMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTimelineDays(entities: List<TimelineDayEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMediaItems(entities: List<MemoriesMediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLoadedDays(entities: List<LoadedDayEntity>)

    @Query("DELETE FROM memories_timeline_days")
    suspend fun deleteTimelineDays()

    @Query("DELETE FROM memories_media WHERE dayId IN (:dayIds)")
    suspend fun deleteMediaItemsForDays(dayIds: Collection<Int>)

    @Query("DELETE FROM memories_loaded_days WHERE dayId IN (:dayIds)")
    suspend fun deleteLoadedDays(dayIds: Collection<Int>)

    @Query("DELETE FROM memories_cache_metadata")
    suspend fun deleteMetadata()

    @Query("DELETE FROM memories_media")
    suspend fun deleteAllMediaItems()

    @Query("DELETE FROM memories_loaded_days")
    suspend fun deleteAllLoadedDays()
}
