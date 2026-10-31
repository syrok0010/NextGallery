package com.syrok0010.nextgallery.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAlias
import com.syrok0010.nextgallery.core.media.MediaAliasKind
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMediaAliasTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NextGalleryDatabase
    private lateinit var registry: RoomMediaIdentityRegistry
    private val remote = MediaSourceIdentity(MediaSourceKind.Memories, "42")
    private val local = MediaSourceIdentity(MediaSourceKind.Local, "content://media/1")
    private val oldAuid = MediaAlias(MediaAliasKind.Auid, "old-auid")
    private val newAuid = MediaAlias(MediaAliasKind.Auid, "new-auid")
    private val oldBuid = MediaAlias(MediaAliasKind.Buid, "old-buid")
    private val newBuid = MediaAlias(MediaAliasKind.Buid, "new-buid")

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun remoteAuidChangesKeepExactIdentityAndReplaceMatchingAlias() =
        runBlocking {
            val remoteId = resolve(remote, oldAuid)
            assertEquals(remoteId, resolve(remote, newAuid))
            assertNotEquals(remoteId, resolve(local, oldAuid))
            assertEquals(remoteId, resolve(remote, newAuid))

            val freshLocal = local.copy(sourceKey = "content://media/2")
            val matchedId = resolve(freshLocal, newAuid)
            assertEquals(matchedId, resolve(remote, newAuid))
            assertTrue(registry.conflicts().isEmpty())
        }

    @Test
    fun localBuidChangesKeepExactIdentityAndReplaceMatchingAlias() =
        runBlocking {
            val localId = resolve(local, oldBuid)
            assertEquals(localId, resolve(local, newBuid))
            assertNotEquals(localId, resolve(remote, oldBuid))
            assertEquals(localId, resolve(remote.copy(sourceKey = "43"), newBuid))
        }

    @Test
    fun obsoleteAliasesAreRemovedBeforeMatchingOtherCandidatesInSameBatch() =
        runBlocking {
            val remoteId = resolve(remote, oldAuid)
            val result = registry.resolve(
                listOf(
                    candidate(local, oldAuid),
                    candidate(remote, newAuid),
                ),
            )
            assertEquals(remoteId, result.mediaIds.getValue(remote))
            assertNotEquals(remoteId, result.mediaIds.getValue(local))
        }

    @Test
    fun linkedCopiesRetainSharedAliasUntilBothCopiesStopObservingIt() =
        runBlocking {
            val localId = resolve(local, oldAuid, oldBuid)
            assertTrue(registry.link(local, remote))
            assertEquals(localId, resolve(remote, oldAuid, oldBuid))

            assertEquals(localId, resolve(remote, newAuid, newBuid))
            val anotherLocal = local.copy(sourceKey = "content://media/2")
            assertEquals(localId, resolve(anotherLocal, oldAuid, oldBuid))
            assertEquals(localId, resolve(local, newAuid, newBuid))
            assertEquals(localId, resolve(anotherLocal, newAuid, newBuid))

            assertNotEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/3"), oldAuid, oldBuid),
            )
            assertEquals(localId, resolve(remote, newAuid, newBuid))
        }

    @Test
    fun emptyObservationsRetireBothAliasesWithoutBreakingExplicitLink() =
        runBlocking {
            val localId = resolve(local)
            assertTrue(registry.link(local, remote))
            resolve(remote, oldAuid, oldBuid)
            assertEquals(localId, resolve(remote))
            assertNotEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/2"), oldAuid, oldBuid),
            )
            assertEquals(localId, resolve(local))
        }

    @Test
    fun exactLinkSurvivesConflictWhileObsoleteAliasesAreRetired() =
        runBlocking {
            val localId = resolve(local)
            assertTrue(registry.link(local, remote))
            resolve(remote, oldAuid)
            val otherLocal = local.copy(sourceKey = "content://media/2")
            val otherId = resolve(otherLocal, newAuid)

            assertEquals(localId, resolve(remote, newAuid))
            assertEquals(setOf(localId, otherId), registry.conflicts().single().conflictingMediaIds)
            assertNotEquals(localId, resolve(local.copy(sourceKey = "content://media/3"), oldAuid))
            assertEquals(localId, resolve(local))
            assertEquals(localId, resolve(remote, newAuid))
        }

    @Test
    fun replacingAliasesSurvivesReopenAndRepeatedIndexingIsIdempotent() =
        runBlocking {
            val localId = resolve(local)
            assertTrue(registry.link(local, remote))
            resolve(remote, oldAuid, oldBuid)
            resolve(remote, newAuid, newBuid)
            database.close()
            openDatabase()

            repeat(3) {
                assertEquals(localId, resolve(remote, newAuid, newBuid))
            }
            database.close()
            openDatabase()
            assertNotEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/2"), oldAuid, oldBuid),
            )
            assertEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/3"), newAuid, newBuid),
            )
            assertEquals(localId, resolve(local))
            assertEquals(localId, resolve(remote, newAuid, newBuid))
            assertTrue(registry.conflicts().isEmpty())
        }

    @Test
    fun aliasesOfAlreadyIndexedRemoteCopyFollowExplicitLinkAndCanBeRetired() =
        runBlocking {
            val remoteId = resolve(remote, oldAuid, oldBuid)
            val localId = resolve(local)
            assertNotEquals(remoteId, localId)

            assertTrue(registry.link(local, remote))
            assertEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/2"), oldAuid, oldBuid),
            )
            resolve(remote, newAuid, newBuid)
            resolve(local.copy(sourceKey = "content://media/2"))

            assertNotEquals(
                localId,
                resolve(local.copy(sourceKey = "content://media/3"), oldAuid, oldBuid),
            )
            assertEquals(localId, resolve(remote, newAuid, newBuid))
        }

    @Test
    fun removingRemoteSourceDoesNotLeaveItsAliasesOnLinkedLocalObject() =
        runBlocking {
            val localId = resolve(local, newBuid)
            assertTrue(registry.link(local, remote))
            resolve(remote, oldAuid)

            registry.removeSource(MediaSourceKind.Memories)

            assertNotEquals(localId, resolve(local.copy(sourceKey = "content://media/2"), oldAuid))
            assertEquals(localId, resolve(remote.copy(sourceKey = "43"), newBuid))
        }

    private fun openDatabase() {
        database = Room
            .databaseBuilder(
                context,
                NextGalleryDatabase::class.java,
                DATABASE_NAME,
            ).build()
        registry = RoomMediaIdentityRegistry(database)
    }

    private fun candidate(source: MediaSourceIdentity, vararg aliases: MediaAlias) =
        MediaIdentityCandidate(source, aliases = aliases.toSet())

    private suspend fun resolve(source: MediaSourceIdentity, vararg aliases: MediaAlias): MediaId =
        registry.resolve(listOf(candidate(source, *aliases))).mediaIds.getValue(source)

    private companion object {
        const val DATABASE_NAME = "room-media-alias-test.db"
    }
}
