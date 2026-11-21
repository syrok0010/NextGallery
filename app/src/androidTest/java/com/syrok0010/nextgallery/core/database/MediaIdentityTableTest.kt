package com.syrok0010.nextgallery.core.database

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAlias
import com.syrok0010.nextgallery.core.media.MediaAliasKind
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.feature.upload.UploadedCopiesFollowUp
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaIdentityTableTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NextGalleryDatabase
    private lateinit var dao: MediaIdentityDao
    private lateinit var registry: RoomMediaIdentityRegistry

    @Before fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    private fun openDatabase() {
        database = Room
            .databaseBuilder(
                context,
                NextGalleryDatabase::class.java,
                DATABASE_NAME,
            ).build()
        dao = database.mediaIdentityDao()
        registry = RoomMediaIdentityRegistry(database)
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test fun nullableSlotsAllowSeveralLocalOnlyAndCloudOnlyObjects() =
        runBlocking {
            val rows = listOf(
                MediaIdentityEntity("local-1", localUri = "uri-1"),
                MediaIdentityEntity("local-2", localUri = "uri-2"),
                MediaIdentityEntity("remote-1", fileId = 1),
                MediaIdentityEntity("remote-2", fileId = 2),
            )
            dao.insert(rows)
            assertEquals(rows.toSet(), dao.byMediaIds(rows.map { it.mediaId }).toSet())
        }

    @Test fun duplicateMediaIdUriOrFileIdIsRejectedWithoutReplacingExistingRows() =
        runBlocking {
            val first = MediaIdentityEntity("first", "uri-1", 1)
            val second = MediaIdentityEntity("second", "uri-2", 2)
            dao.insert(listOf(first, second))
            rejectsConstraint {
                database.openHelper.writableDatabase.execSQL(
                    "INSERT INTO media_identities (mediaId, localUri) VALUES (NULL, 'null-id')",
                )
            }
            for (duplicate in listOf(
                MediaIdentityEntity("first", "uri-3", 3),
                MediaIdentityEntity("third", "uri-1", 3),
                MediaIdentityEntity("third", "uri-3", 1),
            )) {
                rejectsConstraint { dao.insert(listOf(duplicate)) }
                assertEquals(
                    setOf(first, second),
                    dao.byMediaIds(listOf("first", "second", "third")).toSet(),
                )
            }
            rejectsConstraint { dao.update(second.copy(localUri = first.localUri)) }
            rejectsConstraint { dao.update(second.copy(fileId = first.fileId)) }
            assertEquals(setOf(first, second), dao.byMediaIds(listOf("first", "second")).toSet())
        }

    @Test fun failedMergeRollsBackRemovalOfStandaloneCloudRow() =
        runBlocking {
            val local = MediaIdentityEntity("local", localUri = "uri-1", localAuid = "local-alias")
            val remote = MediaIdentityEntity("remote", fileId = 42, remoteBuid = "remote-alias")
            dao.insert(listOf(local, remote))
            database.openHelper.writableDatabase.execSQL(
                """
            CREATE TRIGGER reject_link BEFORE UPDATE ON media_identities
            WHEN NEW.localUri IS NOT NULL AND NEW.fileId IS NOT NULL
            BEGIN SELECT RAISE(ABORT, 'test link failure'); END
                """.trimIndent(),
            )
            rejectsConstraint { registry.link(phone("uri-1"), cloud(42)) }
            assertEquals(setOf(local, remote), dao.byMediaIds(listOf("local", "remote")).toSet())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_link")
            assertTrue(registry.link(phone("uri-1"), cloud(42)))
            assertEquals(
                listOf(local.copy(fileId = 42, remoteBuid = "remote-alias")),
                dao.byMediaIds(listOf("local", "remote")),
            )
        }

    @Test fun eachLocalDuplicateLinksToItsOwnUploadedFileAndKeepsThePairOnRefresh() =
        runBlocking {
            val aliases =
                setOf(
                    MediaAlias(MediaAliasKind.Auid, "same-auid"),
                    MediaAlias(MediaAliasKind.Buid, "same-buid"),
                )
            val locals =
                listOf(
                    phone("content://media/external/images/media/1"),
                    phone("content://media/external/images/media/2"),
                )
            val ids = locals.map { source ->
                registry
                    .resolve(
                        listOf(MediaIdentityCandidate(source, aliases = aliases)),
                    ).mediaIds
                    .getValue(source)
            }
            assertNotEquals(ids[0], ids[1])
            assertEquals(locals[1], registry.conflicts().single().source)
            val followUp = UploadedCopiesFollowUp(registry)
            assertTrue(followUp.link(locals[0].sourceKey, 41))
            assertTrue(followUp.link(locals[1].sourceKey, 42))
            val refreshed = registry
                .resolve(
                    listOf(
                        MediaIdentityCandidate(cloud(42), aliases = aliases),
                        MediaIdentityCandidate(cloud(41), aliases = aliases),
                        MediaIdentityCandidate(locals[1], aliases = aliases),
                        MediaIdentityCandidate(locals[0], aliases = aliases),
                    ),
                ).mediaIds
            assertEquals(ids[0], refreshed.getValue(locals[0]))
            assertEquals(ids[0], refreshed.getValue(cloud(41)))
            assertEquals(ids[1], refreshed.getValue(locals[1]))
            assertEquals(ids[1], refreshed.getValue(cloud(42)))
            assertEquals(2, dao.byMediaIds(ids.map { it.value }).size)
            database.close()
            openDatabase()
            val restored = registry
                .resolve(
                    listOf(locals[0], cloud(41), locals[1], cloud(42)).map {
                        MediaIdentityCandidate(it, aliases = aliases)
                    },
                ).mediaIds
            assertEquals(refreshed, restored)
        }

    @Test fun forgettingOneSidePreservesOtherSideAndItsAliasesUntilLastCopyIsForgotten() =
        runBlocking {
            val row =
                MediaIdentityEntity(
                    "shared",
                    "uri-1",
                    42,
                    "local-auid",
                    "local-buid",
                    "remote-auid",
                    "remote-buid",
                )
            dao.insert(listOf(row))
            registry.forget(listOf(phone("uri-1")))
            assertEquals(
                row.copy(localUri = null, localAuid = null, localBuid = null),
                dao.find(cloud(42)),
            )
            assertNull(dao.find(phone("uri-1")))
            registry.forget(listOf(cloud(42)))
            assertTrue(dao.byMediaIds(listOf("shared")).isEmpty())

            dao.insert(listOf(row))
            registry.forget(listOf(cloud(42)))
            assertEquals(
                row.copy(fileId = null, remoteAuid = null, remoteBuid = null),
                dao.find(phone("uri-1")),
            )
            registry.forget(listOf(phone("uri-1")))
            assertTrue(dao.byMediaIds(listOf("shared")).isEmpty())
        }

    private suspend fun rejectsConstraint(action: suspend () -> Unit) {
        try {
            action()
            fail("SQLite must reject the conflicting write")
        } catch (_: SQLiteConstraintException) {
            // Expected from the real SQLite constraints, not a registry precheck.
        }
    }

    private fun phone(uri: String) = MediaSourceIdentity(MediaSourceKind.Local, uri)
    private fun cloud(id: Long) = MediaSourceIdentity(MediaSourceKind.Memories, id.toString())
    private companion object {
        const val DATABASE_NAME = "media-identity-table-test.db"
    }
}
