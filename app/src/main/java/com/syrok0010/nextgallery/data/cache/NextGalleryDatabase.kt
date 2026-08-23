package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MemoriesCacheMetadataEntity::class,
        TimelineDayEntity::class,
        MemoriesMediaEntity::class,
        MediaIdentifierEntity::class,
        MediaIdentityConflictEntity::class,
        LocalMediaEntity::class,
        LoadedDayEntity::class,
        ThumbnailCacheEntity::class,
    ],
    version = 9,
    exportSchema = false,
)
abstract class NextGalleryDatabase : RoomDatabase() {
    abstract fun memoriesTimelineDao(): MemoriesTimelineDao
    abstract fun mediaIdentityDao(): MediaIdentityDao
    abstract fun localMediaDao(): LocalMediaDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao

    companion object {
        fun create(context: Context): NextGalleryDatabase {
            return Room.databaseBuilder(
                context,
                NextGalleryDatabase::class.java,
                DATABASE_NAME,
            )
                .fallbackToDestructiveMigration(true)
                .build()
        }

        private const val DATABASE_NAME = "next-gallery.db"
    }
}
