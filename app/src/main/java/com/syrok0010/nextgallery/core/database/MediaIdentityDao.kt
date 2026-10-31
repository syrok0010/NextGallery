package com.syrok0010.nextgallery.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.syrok0010.nextgallery.core.media.MediaSourceKind

@Dao
interface MediaIdentityDao {
    @Query("SELECT * FROM media_identifiers WHERE kind = :kind AND value IN (:values)")
    suspend fun identifiers(
        kind: MediaIdentifierKind,
        values: Collection<String>,
    ): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_identifiers WHERE mediaId IN (:mediaIds)")
    suspend fun identifiersForMediaIds(mediaIds: Collection<String>): List<MediaIdentifierEntity>

    @Query(
        """
        SELECT DISTINCT alias.kind, alias.value, identifier.mediaId
        FROM media_source_aliases AS alias
        INNER JOIN media_identifiers AS identifier
            ON identifier.kind = alias.sourceKind AND identifier.value = alias.sourceKey
        WHERE alias.kind = :kind AND alias.value IN (:values)
        """,
    )
    suspend fun aliases(
        kind: MediaIdentifierKind,
        values: Collection<String>,
    ): List<MediaIdentifierEntity>

    @Query("SELECT * FROM media_source_aliases WHERE sourceKind = :kind AND sourceKey IN (:keys)")
    suspend fun aliasesForSources(
        kind: MediaIdentifierKind,
        keys: Collection<String>,
    ): List<MediaSourceAliasEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAliases(entities: List<MediaSourceAliasEntity>)

    @Delete
    suspend fun deleteAliases(entities: List<MediaSourceAliasEntity>)

    @Query("DELETE FROM media_source_aliases WHERE sourceKind = :kind")
    suspend fun deleteAliases(kind: MediaIdentifierKind)

    @Query("SELECT DISTINCT sourceKey FROM media_source_aliases WHERE sourceKind = :kind")
    suspend fun aliasSourceKeys(kind: MediaIdentifierKind): List<String>

    @Query("DELETE FROM media_source_aliases WHERE sourceKind = :kind AND sourceKey IN (:keys)")
    suspend fun deleteAliasesForSources(kind: MediaIdentifierKind, keys: Collection<String>)

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
}
