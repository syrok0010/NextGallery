package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        CacheMetadataEntity::class,
        TimelineDayEntity::class,
        MediaItemEntity::class,
        RemoteMediaIdentityEntity::class,
        LocalMediaIdentityEntity::class,
        LoadedDayEntity::class,
        ThumbnailCacheEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class TimelineCacheDatabase : RoomDatabase() {
    abstract fun timelineCacheDao(): TimelineCacheDao

    companion object {
        fun create(context: Context): TimelineCacheDatabase {
            return Room.databaseBuilder(
                context,
                TimelineCacheDatabase::class.java,
                "timeline-cache.db",
            )
                .fallbackToDestructiveMigration(true)
                .build()
        }
    }
}
