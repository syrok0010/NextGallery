package com.syrok0010.nextgallery.core.database

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
class DatabaseSchemaResetTest {
    @Test
    fun previousSchemaIsResetWithoutMigratingUnownedAliases() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "schema-reset-test.db"
        context.deleteDatabase(name)
        try {
            val identity = MediaIdentifierEntity(MediaIdentifierKind.LocalContent, "content://fixture/1", "stable-id")
            Room.databaseBuilder(context, NextGalleryDatabase::class.java, name).build().let { db ->
                db.mediaIdentityDao().upsertIdentifiers(
                    listOf(identity, MediaIdentifierEntity(MediaIdentifierKind.Auid, "stale-alias", identity.mediaId)),
                )
                db.close()
            }
            // v10 stored aliases without their source copy and had no observations table.
            SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.execSQL("DROP TABLE media_source_aliases")
                db.version = 10
            }
            val reset = Room.databaseBuilder(context, NextGalleryDatabase::class.java, name)
                .fallbackToDestructiveMigration(true).build()
            try {
                assertEquals(emptyList<MediaIdentifierEntity>(), reset.mediaIdentityDao().identifiers(identity.kind, listOf(identity.value)))
                assertEquals(emptyList<MediaIdentifierEntity>(), reset.mediaIdentityDao().identifiers(MediaIdentifierKind.Auid, listOf("stale-alias")))
                assertEquals(emptyList<MediaSourceAliasEntity>(), reset.mediaIdentityDao().aliasesForSources(identity.kind, listOf(identity.value)))
            } finally {
                reset.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
