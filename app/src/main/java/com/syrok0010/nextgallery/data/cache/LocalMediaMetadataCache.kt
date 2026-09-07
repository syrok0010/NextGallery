package com.syrok0010.nextgallery.data.cache

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "local_media_metadata")
data class LocalMediaMetadataEntity(
    @PrimaryKey val contentUri: String,
    val fingerprint: String,
    val metadataJson: String,
    val exifComplete: Boolean = true,
)

@Dao
interface LocalMediaMetadataDao {
    @Query("SELECT * FROM local_media_metadata")
    suspend fun load(): List<LocalMediaMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<LocalMediaMetadataEntity>)

    @Query("DELETE FROM local_media_metadata WHERE contentUri = :uri")
    suspend fun delete(uri: String)
}
