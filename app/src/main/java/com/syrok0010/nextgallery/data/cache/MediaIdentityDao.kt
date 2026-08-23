package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syrok0010.nextgallery.domain.media.MediaSourceKind

@Dao
interface MediaIdentityDao {
    @Query("SELECT * FROM media_identifiers WHERE kind = :kind AND value IN (:values)")
    suspend fun identifiers(
        kind: MediaIdentifierKind,
        values: Collection<String>,
    ): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identifiers WHERE mediaId IN (:mediaIds)")
    suspend fun identifiersForMediaIds(mediaIds: Collection<String>): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identity_conflicts")
    suspend fun conflicts(): List<MediaIdentityConflictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentifiers(entities: List<MediaIdentifierEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(entities: List<MediaIdentityConflictEntity>)

    @Query("UPDATE media_identifiers SET mediaId = :toMediaId WHERE mediaId = :fromMediaId")
    suspend fun reassignMediaId(fromMediaId: String, toMediaId: String)

    @Query("DELETE FROM media_identity_conflicts WHERE source = :source AND sourceKey = :sourceKey")
    suspend fun deleteConflict(source: MediaSourceKind, sourceKey: String)

    @Query("DELETE FROM media_identity_conflicts WHERE source = :source")
    suspend fun deleteConflicts(source: MediaSourceKind)

    @Query("DELETE FROM media_identifiers WHERE kind = :kind")
    suspend fun deleteIdentifiers(kind: MediaIdentifierKind)

    @Query(
        """
        DELETE FROM media_identifiers
        WHERE kind IN (:aliasKinds)
            AND mediaId NOT IN (
                SELECT mediaId FROM media_identifiers WHERE kind IN (:sourceKinds)
            )
        """,
    )
    suspend fun deleteAliasesWithoutSource(
        aliasKinds: Collection<MediaIdentifierKind>,
        sourceKinds: Collection<MediaIdentifierKind>,
    )
}
