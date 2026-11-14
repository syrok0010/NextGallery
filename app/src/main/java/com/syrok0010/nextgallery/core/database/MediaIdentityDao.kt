package com.syrok0010.nextgallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syrok0010.nextgallery.core.media.MediaSourceKind

@Dao
interface MediaIdentityDao {
    @Query("SELECT * FROM media_identifiers WHERE source = :source AND sourceKey IN (:keys)")
    suspend fun identifiers(
        source: MediaSourceKind,
        keys: Collection<String>,
    ): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identifiers WHERE mediaId IN (:mediaIds)")
    suspend fun identifiersForMediaIds(mediaIds: Collection<String>): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identifiers WHERE auid IN (:values)")
    suspend fun identifiersByAuid(values: Collection<String>): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identifiers WHERE buid IN (:values)")
    suspend fun identifiersByBuid(values: Collection<String>): List<MediaIdentifierEntity>

    @Query(
        "SELECT sourceKey FROM media_identifiers " +
            "WHERE source = :source AND (auid IS NOT NULL OR buid IS NOT NULL)",
    )
    suspend fun sourceKeysWithAliases(source: MediaSourceKind): List<String>

    /** The copies keep their exact identity; only their aliases stop matching. */
    @Query(
        "UPDATE media_identifiers SET auid = NULL, buid = NULL " +
            "WHERE source = :source AND sourceKey IN (:keys)",
    )
    suspend fun clearAliases(source: MediaSourceKind, keys: Collection<String>)

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

    @Query("DELETE FROM media_identifiers WHERE source = :source AND sourceKey IN (:keys)")
    suspend fun deleteIdentifiers(source: MediaSourceKind, keys: Collection<String>)

    @Query("DELETE FROM media_identity_conflicts WHERE source = :source AND sourceKey IN (:keys)")
    suspend fun deleteConflicts(source: MediaSourceKind, keys: Collection<String>)
}
