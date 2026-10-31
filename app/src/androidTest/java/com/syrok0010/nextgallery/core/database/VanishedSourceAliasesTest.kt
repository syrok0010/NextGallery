package com.syrok0010.nextgallery.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAlias
import com.syrok0010.nextgallery.core.media.MediaAliasKind
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.network.NextcloudTransport
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.library.local.LocalMediaBatch
import com.syrok0010.nextgallery.feature.library.local.LocalMediaChangeObserver
import com.syrok0010.nextgallery.feature.library.local.LocalMediaIndexProgress
import com.syrok0010.nextgallery.feature.library.local.LocalMediaMetadata
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.library.local.LocalMediaReader
import com.syrok0010.nextgallery.feature.library.local.LocalMediaSource
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesRepository
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.HttpException
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class VanishedSourceAliasesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NextGalleryDatabase
    private lateinit var registry: RoomMediaIdentityRegistry
    private lateinit var localProjection: LocalMediaProjectionRepository
    private lateinit var cloudCache: TimelineCacheRepository
    private val aliases = setOf(
        MediaAlias(MediaAliasKind.Auid, "old-auid"),
        MediaAlias(MediaAliasKind.Buid, "old-buid"),
    )
    private val local = MediaSourceIdentity(MediaSourceKind.Local, "content://media/1")
    private val remote = MediaSourceIdentity(MediaSourceKind.Memories, "42")

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
    fun completeLocalScanRetiresAliasesButKeepsExactIdentityAndExplicitLink() = runBlocking {
        val id = saveLocal()
        assertTrue(registry.link(local, remote))

        localProjection.finishLocalMediaReconciliation(emptySet())
        reopenDatabase()

        assertTrue(localProjection.loadLocalMediaProjection().isEmpty())
        assertAliasesRetired(id)
        assertEquals(id, resolve(local, emptySet()))
        assertEquals(id, resolve(remote, emptySet()))
    }

    @Test
    fun disappearingLocalCopyKeepsSharedAliasesOfAnotherCopy() = runBlocking {
        val id = saveLocal()
        assertTrue(registry.link(local, remote))
        resolve(remote)

        localProjection.finishLocalMediaReconciliation(emptySet())

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
        assertEquals(aliases.size, database.mediaIdentityDao().aliasesForSources(
            MediaIdentifierKind.MemoriesFile, listOf(remote.sourceKey),
        ).size)
    }

    @Test
    fun fullLocalScanAlsoRetiresAliasesWithoutProjectionRow() = runBlocking {
        val id = resolve(local)

        localProjection.finishLocalMediaReconciliation(emptySet())

        assertAliasesRetired(id)
        assertEquals(id, resolve(local, emptySet()))
    }

    @Test
    fun unfinishedLocalScanAndReadFailureKeepAliases() = runBlocking {
        val id = saveLocal()
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flow {
                    emit(LocalMediaBatch(emptyList(), LocalMediaIndexProgress(0, 1)))
                    error("MediaStore read failed before completion")
                }
            },
            projectionStore = localProjection,
            identityRegistry = registry,
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        )

        source.updates().first { it.failure }

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
        assertEquals(1, localProjection.loadLocalMediaProjection().size)
    }

    @Test
    fun unavailableLocalRowRetainsAliasesAtCompletedScan() = runBlocking {
        val id = saveLocal()

        localProjection.finishLocalMediaReconciliation(setOf(local.sourceKey))

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
    }

    @Test
    fun localRowWithoutTimelineDateDoesNotCountAsVanished() = runBlocking {
        val id = resolve(local)
        val metadata = LocalMediaMetadata(
            contentUri = local.sourceKey,
            displayName = "photo.jpg",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            sizeBytes = null,
            dateTakenMillis = null,
            dateModifiedSeconds = null,
            dateAddedSeconds = null,
            durationMillis = null,
            isVideo = false,
        )
        val source = LocalMediaSource(
            reader = LocalMediaReader {
                flowOf(LocalMediaBatch(listOf(metadata), LocalMediaIndexProgress(1, 1)))
            },
            projectionStore = localProjection,
            identityRegistry = registry,
            changeObserver = LocalMediaChangeObserver { emptyFlow() },
        )

        source.updates().first { it.processed == LocalMediaIndexProgress(1, 1) }

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
    }

    @Test
    fun completeCloudSnapshotRetiresAliasesAndKeepsExactLink() = runBlocking {
        val id = saveLinkedRemote()

        saveSnapshot(emptyList(), emptyList(), emptySet())
        reopenDatabase()

        assertTrue(database.memoriesTimelineDao().mediaItems().isEmpty())
        assertAliasesRetired(id)
        assertEquals(id, resolve(local, emptySet()))
        assertEquals(id, resolve(remote, emptySet()))
    }

    @Test
    fun disappearingCloudCopyKeepsAliasesOfLinkedLocalCopy() = runBlocking {
        val id = saveLinkedRemote(localAliases = aliases)

        saveSnapshot(emptyList(), emptyList(), emptySet())

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
    }

    @Test
    fun partialCloudDetailsDoNotRetireMissingAliases() = runBlocking {
        val id = saveLinkedRemote()
        val other = item(remote.copy(sourceKey = "43"), resolve(remote.copy(sourceKey = "43"), emptySet()))

        cloudCache.saveDayDetails(listOf(other), emptySet())

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
        assertEquals(2, database.memoriesTimelineDao().mediaItems().size)
    }

    @Test
    fun invalidatedCloudDayKeepsAliasesUntilCompleteSnapshotEvenAfterRestart() = runBlocking {
        val id = saveLinkedRemote()
        saveSnapshot(listOf(TimelineDay(DAY, 2)), emptyList(), emptySet())
        reopenDatabase()

        assertTrue(requireNotNull(cloudCache.loadTimelineSnapshot(credentials)).items.isEmpty())
        assertEquals(aliases.size, database.mediaIdentityDao().aliasesForSources(
            MediaIdentifierKind.MemoriesFile, listOf(remote.sourceKey),
        ).size)
        assertEquals(1, database.memoriesTimelineDao().mediaItems().size)

        saveSnapshot(emptyList(), emptyList(), emptySet())

        assertAliasesRetired(id)
    }

    @Test
    fun completeSnapshotReplacesDayEvenWhenCountDidNotChange() = runBlocking {
        val id = saveLinkedRemote()
        val replacement = remote.copy(sourceKey = "43")
        val replacementItem = item(replacement, resolve(replacement, emptySet()))

        saveSnapshot(listOf(TimelineDay(DAY, 1)), listOf(replacementItem), setOf(DAY))

        assertEquals(listOf(replacementItem), cloudCache.loadTimelineSnapshot(credentials)?.items)
        assertAliasesRetired(id)
    }

    @Test
    fun dayRemovedFromCompleteIndexRetiresItsAliases() = runBlocking {
        val id = saveLinkedRemote()

        saveSnapshot(emptyList(), emptyList(), emptySet())

        assertAliasesRetired(id)
        assertTrue(database.memoriesTimelineDao().mediaItems().isEmpty())
    }

    @Test
    fun vanishedDayWaitsForCompleteTimelineBeforeRetiringAliases() = runBlocking {
        val id = saveLinkedRemote()
        saveSnapshot(listOf(TimelineDay(DAY + 1, 1)), emptyList(), emptySet())
        reopenDatabase()
        assertRemoteAliasesPresent()
        assertTrue(requireNotNull(cloudCache.loadTimelineSnapshot(credentials)).items.isEmpty())

        val other = remote.copy(sourceKey = "43")
        val replacement = item(other, resolve(other, emptySet())).copy(dayId = DAY + 1)
        saveSnapshot(listOf(TimelineDay(DAY + 1, 1)), listOf(replacement), setOf(DAY + 1))

        assertAliasesRetired(id)
        assertEquals(listOf(replacement), cloudCache.loadTimelineSnapshot(credentials)?.items)
    }

    @Test
    fun loadedFlagWithoutAllCountedItemsDoesNotConfirmDisappearance() = runBlocking {
        val id = saveLinkedRemote()
        val other = remote.copy(sourceKey = "43")
        val replacement = item(other, resolve(other, emptySet()))

        saveSnapshot(listOf(TimelineDay(DAY, 2)), listOf(replacement), setOf(DAY))

        assertRemoteAliasesPresent()
        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
    }

    @Test
    fun copyMovedToAnotherDayInSameSnapshotKeepsItsAliases() = runBlocking {
        val id = saveLinkedRemote()
        val moved = item(remote, id).copy(dayId = DAY + 1)

        saveSnapshot(listOf(TimelineDay(DAY + 1, 1)), listOf(moved), setOf(DAY + 1))

        assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
        assertEquals(listOf(moved), cloudCache.loadTimelineSnapshot(credentials)?.items)
    }

    @Test
    fun copyMovedToUnloadedDayKeepsAliasForLocalCopyBetweenRequests() = runBlocking {
        assertCopyMovedAcrossRequests(keepOldDay = false)
    }

    @Test
    fun replacingOldDayBeforeLoadingNewDayKeepsAliasForLocalCopy() = runBlocking {
        assertCopyMovedAcrossRequests(keepOldDay = true)
    }

    private suspend fun assertCopyMovedAcrossRequests(keepOldDay: Boolean) {
        val id = saveLinkedRemote()
        val days = buildList {
            if (keepOldDay) add(TimelineDay(DAY, 1))
            add(TimelineDay(DAY + 1, 1))
        }
        saveSnapshot(days, emptyList(), emptySet())
        if (keepOldDay) cloudCache.saveDayDetails(emptyList(), setOf(DAY))
        reopenDatabase()

        assertRemoteAliasesPresent()
        val unchangedBuid = aliases.filter { it.kind == MediaAliasKind.Buid }.toSet()
        val newLocal = local.copy(sourceKey = "content://media/2")
        assertEquals(id, resolve(newLocal, unchangedBuid))

        val movedAliases = unchangedBuid + MediaAlias(MediaAliasKind.Auid, "moved-auid")
        assertEquals(id, resolve(remote, movedAliases))
        val moved = item(remote, id).copy(dayId = DAY + 1, auid = "moved-auid")
        cloudCache.saveDayDetails(listOf(moved), setOf(DAY + 1))

        assertEquals(id, resolve(newLocal, unchangedBuid))
        assertEquals(listOf(moved), cloudCache.loadTimelineSnapshot(credentials)?.items)
        assertTrue(registry.conflicts().isEmpty())
    }

    @Test
    fun partialInitialResponseAndSeparateDayResponsesKeepAliases() = runBlocking {
        val id = saveLinkedRemote()
        CloudResponses().use { server ->
            val onlineCredentials = credentials.copy(serverUrl = server.origin)
            val repository = MemoriesRepository(
                NextcloudTransport(Json { ignoreUnknownKeys = true }), cloudCache, registry,
            )

            val partial = repository.loadInitialTimeline(onlineCredentials)

            assertTrue(partial.loadedDayIds.isEmpty())
            assertEquals(1, partial.items.size)
            assertTrue(requireNotNull(cloudCache.loadTimelineSnapshot(onlineCredentials)).items.isEmpty())
            assertRemoteAliasesPresent()

            try {
                repository.loadTimelineDays(onlineCredentials, listOf(DAY))
                error("Expected failed day request")
            } catch (error: HttpException) {
                assertEquals(503, error.code())
            }
            assertRemoteAliasesPresent()

            server.failDetails = false
            val full = repository.loadTimelineDays(onlineCredentials, listOf(DAY))

            assertEquals(listOf(43L), full.map { it.remoteFileId })
            assertEquals(full, cloudCache.loadTimelineSnapshot(onlineCredentials)?.items)
            assertRemoteAliasesPresent()
            assertEquals(id, resolve(local.copy(sourceKey = "content://media/2")))
        }
    }

    private suspend fun assertRemoteAliasesPresent() {
        assertEquals(aliases.size, database.mediaIdentityDao().aliasesForSources(
            MediaIdentifierKind.MemoriesFile, listOf(remote.sourceKey),
        ).size)
    }

    private suspend fun saveLocal(): MediaId {
        val id = resolve(local)
        localProjection.saveLocalMediaBatch(listOf(item(local, id)))
        return id
    }

    private suspend fun saveLinkedRemote(localAliases: Set<MediaAlias> = emptySet()): MediaId {
        val id = resolve(local, localAliases)
        assertTrue(registry.link(local, remote))
        resolve(remote)
        saveSnapshot(listOf(TimelineDay(DAY, 1)), listOf(item(remote, id)), setOf(DAY))
        return id
    }

    private suspend fun saveSnapshot(days: List<TimelineDay>, items: List<MediaItem>, loaded: Set<Int>) {
        cloudCache.saveTimelineSnapshot(
            credentials,
            TimelineSnapshotAssembler.assemble(
                config = MemoriesConfig("10.1.0", "/Photos", false, false, false, false, false, false),
                days = days,
                mediaItems = items,
                loadedDayIds = loaded,
            ),
        )
    }

    private suspend fun assertAliasesRetired(id: MediaId) {
        aliases.forEachIndexed { index, alias ->
            assertNotEquals(id, resolve(local.copy(sourceKey = "content://media/new-$index"), setOf(alias)))
        }
    }

    private suspend fun resolve(source: MediaSourceIdentity, observed: Set<MediaAlias> = aliases): MediaId =
        registry.resolve(listOf(MediaIdentityCandidate(source, aliases = observed))).mediaIds.getValue(source)

    private fun item(source: MediaSourceIdentity, id: MediaId) = MediaItem(
        mediaId = id,
        dayId = DAY,
        displayName = "photo.jpg",
        mimeType = "image/jpeg",
        width = 400,
        height = 300,
        etag = null,
        livePhotoId = null,
        auid = "old-auid",
        buid = "old-buid",
        sharedBy = null,
        takenAtEpochSeconds = DAY * 86_400L,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = if (source.source == MediaSourceKind.Local) {
            MediaAssetRef.LocalContent(source.sourceKey, null)
        } else {
            MediaAssetRef.MemoriesFile(source.sourceKey.toLong())
        },
    )

    private fun reopenDatabase() {
        database.close()
        openDatabase()
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
        registry = RoomMediaIdentityRegistry(database)
        localProjection = LocalMediaProjectionRepository(database)
        cloudCache = TimelineCacheRepository(database, ThumbnailFileStore(context), registry)
    }

    private class CloudResponses : AutoCloseable {
        private val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newSingleThreadExecutor()
        val origin = "http://127.0.0.1:${server.localPort}"
        @Volatile var failDetails = true

        init {
            executor.execute {
                while (!server.isClosed) {
                    val socket = try {
                        server.accept()
                    } catch (_: IOException) {
                        break
                    }
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val request = requireNotNull(reader.readLine())
                        while (!reader.readLine().isNullOrEmpty()) { /* consume headers */ }
                        val photo = """{"fileid":43,"dayid":20000,"epoch":1728000000,"size":2048}"""
                        val (status, body) = when {
                            request.contains("/config ") -> 200 to """{"version":"10.1.0"}"""
                            request.contains("/days ") -> 200 to """[{"dayid":20000,"count":2,"detail":[$photo]}]"""
                            failDetails -> 503 to "{}"
                            else -> 200 to "[$photo]"
                        }
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write("HTTP/1.1 $status Fixture\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            server.close()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val DATABASE_NAME = "vanished-source-aliases-test.db"
        const val DAY = 20_000
        val credentials = AccountCredentials("https://cloud.example.test", "test", "test", "test")
    }
}
