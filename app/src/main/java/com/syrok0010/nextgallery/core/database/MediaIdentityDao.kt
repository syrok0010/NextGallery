package com.syrok0010.nextgallery.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind

@Dao
interface MediaIdentityDao {
    @Query("SELECT * FROM media_identities WHERE localUri IN (:uris)")
    suspend fun byLocalUris(uris: Collection<String>): List<MediaIdentityEntity>

    @Query("SELECT * FROM media_identities WHERE fileId IN (:fileIds)")
    suspend fun byFileIds(fileIds: Collection<Long>): List<MediaIdentityEntity>

    @Query("SELECT * FROM media_identities WHERE mediaId IN (:mediaIds)")
    suspend fun byMediaIds(mediaIds: Collection<String>): List<MediaIdentityEntity>

    @Query("SELECT * FROM media_identities WHERE localAuid IN (:values) OR remoteAuid IN (:values)")
    suspend fun byAuid(values: Collection<String>): List<MediaIdentityEntity>

    @Query("SELECT * FROM media_identities WHERE localBuid IN (:values) OR remoteBuid IN (:values)")
    suspend fun byBuid(values: Collection<String>): List<MediaIdentityEntity>

    suspend fun find(source: MediaSourceIdentity): MediaIdentityEntity? =
        when (source.source) {
            MediaSourceKind.Local -> byLocalUris(listOf(source.sourceKey)).singleOrNull()
            MediaSourceKind.Memories -> byFileIds(listOf(source.sourceKey.toLong())).singleOrNull()
        }

    @Query(
        "SELECT localUri FROM media_identities WHERE localUri IS NOT NULL AND (localAuid IS NOT NULL OR localBuid IS NOT NULL)",
    )
    suspend fun localUrisWithAliases(): List<String>

    @Query(
        "UPDATE media_identities SET localAuid = NULL, localBuid = NULL WHERE localUri IN (:uris)",
    )
    suspend fun clearLocalAliases(uris: Collection<String>)

    @Query(
        "UPDATE media_identities SET remoteAuid = NULL, remoteBuid = NULL WHERE fileId IN (:fileIds)",
    )
    suspend fun clearRemoteAliases(fileIds: Collection<Long>)

    // A uniqueness conflict must fail the transaction, never replace another object's row.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entities: List<MediaIdentityEntity>)

    @Update(onConflict = OnConflictStrategy.ABORT)
    suspend fun update(entity: MediaIdentityEntity)

    @Query("DELETE FROM media_identities WHERE mediaId IN (:mediaIds)")
    suspend fun delete(mediaIds: Collection<String>)

    @Query("SELECT * FROM media_identity_conflicts")
    suspend fun conflicts(): List<MediaIdentityConflictEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConflicts(entities: List<MediaIdentityConflictEntity>)

    @Query("DELETE FROM media_identity_conflicts WHERE source = :source AND sourceKey = :sourceKey")
    suspend fun deleteConflict(source: MediaSourceKind, sourceKey: String)
}
