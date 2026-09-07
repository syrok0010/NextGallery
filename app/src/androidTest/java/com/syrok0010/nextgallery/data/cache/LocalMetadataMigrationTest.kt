package com.syrok0010.nextgallery.data.cache

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalMetadataMigrationTest {
    @Test
    fun migrationFromNinePreservesPersistentIdentity() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "local-metadata-migration-test.db"
        context.deleteDatabase(name)
        try {
            val identity = MediaIdentifierEntity(MediaIdentifierKind.LocalContent, "content://fixture/1", "stable-id")
            Room.databaseBuilder(context, NextGalleryDatabase::class.java, name).build().let { db ->
                db.mediaIdentityDao().upsertIdentifiers(listOf(identity))
                db.close()
            }
            // v9 has the same existing tables; only the new metadata table is absent.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE local_media_metadata")
                db.version = 9
            }
            val migrated = Room.databaseBuilder(context, NextGalleryDatabase::class.java, name)
                .addMigrations(NextGalleryDatabase.MIGRATION_9_10).build()
            try {
                assertEquals(emptyList<LocalMediaMetadataEntity>(), migrated.localMediaMetadataDao().load())
                assertEquals(listOf(identity), migrated.mediaIdentityDao().identifiers(identity.kind, listOf(identity.value)))
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
