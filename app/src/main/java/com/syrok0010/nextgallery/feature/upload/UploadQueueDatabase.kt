package com.syrok0010.nextgallery.feature.upload

import android.content.Context
import androidx.room.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json

private val json = Json

/** Nested values are stored as JSON; the journal is never queried by them. */
internal class UploadConverters {
    @TypeConverter fun fromSelection(v: UploadSelection): String = json.encodeToString(v)

    @TypeConverter fun toSelection(v: String): UploadSelection = json.decodeFromString(v)

    @TypeConverter fun fromAlbum(v: UploadAlbum?): String? = v?.let(json::encodeToString)

    @TypeConverter fun toAlbum(v: String?): UploadAlbum? = v?.let(json::decodeFromString)

    @TypeConverter fun fromReceipt(v: UploadReceipt?): String? = v?.let(json::encodeToString)

    @TypeConverter fun toReceipt(v: String?): UploadReceipt? = v?.let(json::decodeFromString)

    @TypeConverter fun fromPath(v: List<String>?): String? = v?.let(json::encodeToString)

    @TypeConverter fun toPath(v: String?): List<String>? = v?.let(json::decodeFromString)

    @TypeConverter fun fromSteps(v: Map<FollowUp, StepState>): String = json.encodeToString(v)

    @TypeConverter fun toSteps(v: String): Map<FollowUp, StepState> = json.decodeFromString(v)
}

@Dao
internal interface UploadJournalDao {
    @Query("SELECT * FROM upload_jobs WHERE owner = :owner ORDER BY rowid")
    suspend fun jobs(owner: String): List<UploadJob>

    @Query("SELECT * FROM upload_jobs WHERE owner = :owner AND hidden = 0 ORDER BY rowid")
    fun observe(owner: String): Flow<List<UploadJob>>

    @Query("SELECT * FROM upload_jobs WHERE id = :id")
    suspend fun job(id: String): UploadJob?

    @Insert suspend fun insert(jobs: List<UploadJob>)

    @Update suspend fun update(jobs: List<UploadJob>)

    @Query("DELETE FROM upload_jobs")
    suspend fun clear()
}

/** Bump [version] on every schema change: the destructive fallback runs only when it changes. */
@Database(entities = [UploadJob::class], version = 2, exportSchema = false)
@TypeConverters(UploadConverters::class)
internal abstract class UploadQueueDatabase : RoomDatabase() {
    abstract fun journal(): UploadJournalDao

    companion object {
        /** Apart from the cache DB, which is rebuilt freely; a schema change still resets it, the app is not released yet. */
        fun open(context: Context, name: String = "upload-journal.db"): UploadQueueDatabase =
            Room
                .databaseBuilder(context, UploadQueueDatabase::class.java, name)
                .fallbackToDestructiveMigration(true)
                .build()
    }
}
