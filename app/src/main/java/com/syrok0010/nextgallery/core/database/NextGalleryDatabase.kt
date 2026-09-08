package com.syrok0010.nextgallery.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MemoriesCacheMetadataEntity::class,
        TimelineDayEntity::class,
        MemoriesMediaEntity::class,
        MediaIdentifierEntity::class,
        MediaIdentityConflictEntity::class,
        LocalMediaEntity::class,
        LocalMediaMetadataEntity::class,
        LoadedDayEntity::class,
        ThumbnailCacheEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class NextGalleryDatabase : RoomDatabase() {
    abstract fun memoriesTimelineDao(): MemoriesTimelineDao
    abstract fun mediaIdentityDao(): MediaIdentityDao
    abstract fun localMediaMetadataDao(): LocalMediaMetadataDao
    abstract fun localMediaDao(): LocalMediaDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao

    companion object {
        fun create(context: Context): NextGalleryDatabase {
            return Room.databaseBuilder(
                context,
                NextGalleryDatabase::class.java,
                DATABASE_NAME,
            )
                .addMigrations(MIGRATION_9_10)
                .fallbackToDestructiveMigration(true)
                .build()
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """CREATE TABLE IF NOT EXISTS local_media_metadata (
                        contentUri TEXT NOT NULL PRIMARY KEY,
                        fingerprint TEXT NOT NULL,
                        metadataJson TEXT NOT NULL,
                        exifComplete INTEGER NOT NULL
                    )""".trimIndent(),
                )
            }
        }

        private const val DATABASE_NAME = "next-gallery.db"
    }
}
