package com.syrok0010.nextgallery.core.database

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
        MediaSourceAliasEntity::class,
        MediaIdentityConflictEntity::class,
        LocalMediaEntity::class,
        LocalMediaMetadataEntity::class,
        LoadedDayEntity::class,
        ThumbnailCacheEntity::class,
    ],
    version = 11,
    exportSchema = false,
)
abstract class NextGalleryDatabase : RoomDatabase() {
    abstract fun memoriesTimelineDao(): MemoriesTimelineDao
    abstract fun mediaIdentityDao(): MediaIdentityDao
    abstract fun localMediaMetadataDao(): LocalMediaMetadataDao
    abstract fun localMediaDao(): LocalMediaDao
    abstract fun thumbnailCacheDao(): ThumbnailCacheDao

    companion object {
        fun create(context: Context): NextGalleryDatabase =
            Room
                .databaseBuilder(
                    context,
                    NextGalleryDatabase::class.java,
                    DATABASE_NAME,
                ).fallbackToDestructiveMigration(true)
                .build()

        private const val DATABASE_NAME = "next-gallery.db"
    }
}
