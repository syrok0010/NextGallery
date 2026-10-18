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
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.library.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomMediaIdentityRegistryTest {
    private lateinit var context: Context
    private lateinit var database: NextGalleryDatabase
    private lateinit var registry: RoomMediaIdentityRegistry
    private lateinit var cacheRepository: TimelineCacheRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openDatabase()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun localContentUriKeepsMediaIdAcrossRegistryInstances() = runBlocking {
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val first = registry.resolveId(local)

        database.close()
        openDatabase(mediaIdFactory = { error("Existing local identity must not generate a new MediaId") })

        assertEquals(first, registry.resolveId(local))
    }

    @Test
    fun sourceIdentityIsStableAndScopedBySource() = runBlocking {
        database.close()
        val generatedIds = listOf(MediaId("remote-id"), MediaId("local-id")).iterator()
        openDatabase(mediaIdFactory = generatedIds::next)
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, "42")
        val local = MediaSourceIdentity(MediaSourceKind.Local, "42")

        val first = registry.resolveIds(listOf(remote, local))

        database.close()
        openDatabase(mediaIdFactory = { error("Existing identities must not generate new MediaIds") })

        assertEquals(first, registry.resolveIds(listOf(remote, local)))
        assertEquals(2, first.values.toSet().size)
    }

    @Test
    fun remoteMediaIdSurvivesDatabaseReopenAndNetworkRefresh() = runBlocking {
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val firstMediaId = registry.resolveId(remote)
        cacheRepository.saveTimelineSnapshot(credentials, snapshot(mediaItem(firstMediaId)))

        database.close()
        openDatabase(mediaIdFactory = { error("Existing identity must not generate a new MediaId") })

        val refreshedMediaId = registry.resolveId(remote)

        assertEquals(MediaId("first-persistent-id"), firstMediaId)
        assertEquals(firstMediaId, refreshedMediaId)

        cacheRepository.saveTimelineSnapshot(credentials, snapshot(mediaItem(refreshedMediaId)))

        assertEquals(
            MediaId("first-persistent-id"),
            cacheRepository.loadTimelineSnapshot(credentials)?.items?.single()?.mediaId,
        )
    }

    @Test
    fun sourceProjectionsReadReassignedMediaIdFromIdentityStorage() = runBlocking {
        val sharedAlias = MediaAlias(MediaAliasKind.Auid, "shared-auid")
        val remoteSource = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val remoteId = registry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = remoteSource,
                    publishedMediaId = MediaId("remote-id"),
                    aliases = setOf(sharedAlias),
                ),
            ),
        ).mediaIds.getValue(remoteSource)
        cacheRepository.saveTimelineSnapshot(credentials, snapshot(mediaItem(remoteId)))

        val local = localMediaItem(MediaId("local-id"))
        val localSource = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        registry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = localSource,
                    publishedMediaId = local.mediaId,
                    aliases = setOf(sharedAlias),
                ),
            ),
        )
        val localRepository = LocalMediaProjectionRepository(database)
        localRepository.saveLocalMediaBatch(listOf(local))

        assertEquals(
            MediaId("local-id"),
            cacheRepository.loadTimelineSnapshot(credentials)?.items?.single()?.mediaId,
        )
        assertEquals(MediaId("local-id"), localRepository.loadLocalMediaProjection().single().mediaId)
    }

    @Test
    fun linkedServerFileKeepsLocalMediaIdAcrossReopenAndCache() = runBlocking {
        database.close()
        val generatedIds = listOf(MediaId("local-id"), MediaId("remote-id")).iterator()
        openDatabase(mediaIdFactory = generatedIds::next)
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val localId = registry.resolveId(local)
        val earlyRemoteId = registry.resolveId(remote)
        cacheRepository.saveTimelineSnapshot(credentials, snapshot(mediaItem(earlyRemoteId)))

        assertEquals(true, registry.link(existing = local, linked = remote))

        assertEquals(MediaId("remote-id"), earlyRemoteId)
        assertEquals(
            localId,
            cacheRepository.loadTimelineSnapshot(credentials)?.items?.single()?.mediaId,
        )
        database.close()
        openDatabase(mediaIdFactory = { error("Linked identities must not generate new MediaIds") })
        assertEquals(localId, registry.resolveId(remote))
        assertEquals(localId, registry.resolveId(local))
    }

    @Test
    fun linkOutranksAliasesAndKeepsOneServerCopy() = runBlocking {
        database.close()
        val generatedIds = listOf("local-id", "early-remote-id", "first-copy-id").map(::MediaId).iterator()
        openDatabase(mediaIdFactory = generatedIds::next)
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val first = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val second = MediaSourceIdentity(MediaSourceKind.Memories, (FILE_ID + 1).toString())
        val serverAlias = MediaAlias(MediaAliasKind.Auid, "server-auid")
        val localId = registry.resolveId(local)
        registry.resolve(listOf(MediaIdentityCandidate(source = second, aliases = setOf(serverAlias))))

        assertEquals(true, registry.link(existing = local, linked = first))
        assertEquals(true, registry.link(existing = local, linked = second))

        val resolved = registry.resolve(
            listOf(
                MediaIdentityCandidate(source = second, aliases = setOf(serverAlias)),
                MediaIdentityCandidate(source = first, aliases = emptySet()),
            ),
        ).mediaIds
        assertEquals(localId, resolved.getValue(second))
        assertEquals(MediaId("first-copy-id"), resolved.getValue(first))
    }

    @Test
    fun serverDuplicatesWithOneAliasJoinPhoneFileOnlyOnce() = runBlocking {
        database.close()
        val generatedIds = listOf("local-id", "first-id", "second-id").map(::MediaId).iterator()
        openDatabase(mediaIdFactory = generatedIds::next)
        val alias = MediaAlias(MediaAliasKind.Auid, "shared-auid")
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val first = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        val second = MediaSourceIdentity(MediaSourceKind.Memories, (FILE_ID + 1).toString())
        fun candidate(source: MediaSourceIdentity) = MediaIdentityCandidate(source = source, aliases = setOf(alias))
        val localId = registry.resolve(listOf(candidate(local))).mediaIds.getValue(local)
        assertEquals(localId, registry.resolve(listOf(candidate(first))).mediaIds.getValue(first))

        database.close()
        openDatabase(mediaIdFactory = listOf(MediaId("second-id")).iterator()::next)
        val secondId = registry.resolve(listOf(candidate(second))).mediaIds.getValue(second)

        assertEquals(MediaId("second-id"), secondId)
        val resolved = registry.resolve(listOf(candidate(second), candidate(first), candidate(local))).mediaIds
        assertEquals(mapOf(local to localId, first to localId, second to secondId), resolved)
    }

    @Test
    fun staleLinkIsNotWritten() = runBlocking {
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())
        registry.resolveId(local)

        assertEquals(false, registry.link(existing = local, linked = remote, isCurrent = { false }))
        assertEquals(
            emptyList<MediaIdentifierEntity>(),
            database.mediaIdentityDao().identifiers(MediaIdentifierKind.MemoriesFile, listOf(FILE_ID.toString())),
        )
    }

    @Test
    fun unknownExistingSourceIsNotLinked() = runBlocking {
        val local = MediaSourceIdentity(MediaSourceKind.Local, LOCAL_CONTENT_URI)
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, FILE_ID.toString())

        assertEquals(false, registry.link(existing = local, linked = remote))
        assertEquals(MediaId("first-persistent-id"), registry.resolveId(remote))
    }

    private fun openDatabase(
        mediaIdFactory: () -> MediaId = { MediaId("first-persistent-id") },
    ) {
        database = Room.databaseBuilder(
            context,
            NextGalleryDatabase::class.java,
            DATABASE_NAME,
        ).build()
        registry = RoomMediaIdentityRegistry(database, mediaIdFactory)
        cacheRepository = TimelineCacheRepository(
            database = database,
            thumbnailFileStore = ThumbnailFileStore(context),
            identityRegistry = registry,
        )
    }

    private suspend fun RoomMediaIdentityRegistry.resolveId(source: MediaSourceIdentity): MediaId =
        resolveIds(listOf(source)).getValue(source)

    private suspend fun RoomMediaIdentityRegistry.resolveIds(
        sources: List<MediaSourceIdentity>,
    ): Map<MediaSourceIdentity, MediaId> = resolve(
        sources.map { source -> MediaIdentityCandidate(source = source, aliases = emptySet()) },
    ).mediaIds

    private fun snapshot(item: MediaItem) = TimelineSnapshotAssembler.assemble(
        config = MemoriesConfig(
            version = "8.0.1",
            timelinePath = "/Photos",
            albumsEnabled = false,
            recognizeEnabled = false,
            faceRecognitionEnabled = false,
            previewGeneratorEnabled = false,
            stackRawFiles = false,
            dedupIdentical = false,
        ),
        days = listOf(TimelineDay(dayId = DAY_ID, count = 1)),
        mediaItems = listOf(item),
        loadedDayIds = setOf(DAY_ID),
    )

    private fun mediaItem(mediaId: MediaId) = MediaItem(
        mediaId = mediaId,
        dayId = DAY_ID,
        displayName = "IMG_0042.jpg",
        mimeType = "image/jpeg",
        width = 4032,
        height = 3024,
        etag = "etag-42",
        livePhotoId = null,
        auid = "auid-42",
        buid = "buid-42",
        sharedBy = null,
        takenAtEpochSeconds = 1_717_100_000L,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(photoFileId = FILE_ID),
    )

    private fun localMediaItem(mediaId: MediaId) = mediaItem(mediaId).copy(
        auid = "shared-auid",
        assetRef = MediaAssetRef.LocalContent(
            contentUri = LOCAL_CONTENT_URI,
            modifiedAtEpochSeconds = 1_717_100_000L,
        ),
    )

    private companion object {
        const val LOCAL_CONTENT_URI = "content://media/external/images/media/42"
        const val DATABASE_NAME = "room-media-identity-registry-test.db"
        const val DAY_ID = 19_870
        const val FILE_ID = 42L

        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.test",
            loginName = "test-user",
            appPassword = "test-password",
            userId = "test-user",
        )
    }
}
