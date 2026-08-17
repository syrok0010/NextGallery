package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalMediaDao {
    @Query(
        """
        SELECT media.*, identifier.mediaId
        FROM local_media_projection AS media
        INNER JOIN media_identifiers AS identifier
            ON identifier.kind = :identifierKind
            AND identifier.value = media.contentUri
        ORDER BY media.takenAtEpochSeconds DESC, identifier.mediaId DESC
        """,
    )
    suspend fun projection(
        identifierKind: MediaIdentifierKind = MediaIdentifierKind.LocalContent,
    ): List<IdentifiedLocalMedia>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<LocalMediaEntity>)

    @Query("DELETE FROM local_media_projection WHERE contentUri NOT IN (:contentUris)")
    suspend fun deleteNotIn(contentUris: Collection<String>)

    @Query("DELETE FROM local_media_projection")
    suspend fun deleteAll()
}
