package com.syrok0010.nextgallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syrok0010.nextgallery.core.media.MediaSourceKind

@Dao
interface LocalMediaDao {
    @Query(
        """
        SELECT media.*, identifier.mediaId
        FROM local_media_projection AS media
        INNER JOIN media_identifiers AS identifier
            ON identifier.source = :source
            AND identifier.sourceKey = media.contentUri
        ORDER BY media.takenAtEpochSeconds DESC, identifier.mediaId DESC
        """,
    )
    suspend fun projection(
        source: MediaSourceKind = MediaSourceKind.Local,
    ): List<IdentifiedLocalMedia>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entities: List<LocalMediaEntity>)

    @Query("DELETE FROM local_media_projection WHERE contentUri NOT IN (:contentUris)")
    suspend fun deleteNotIn(contentUris: Collection<String>)

    @Query("SELECT contentUri FROM local_media_projection")
    suspend fun contentUris(): List<String>

    @Query("DELETE FROM local_media_projection WHERE contentUri IN (:uris)")
    suspend fun deleteUris(uris: List<String>)
}
