package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaAlias
import com.syrok0010.nextgallery.data.memories.MediaAliasKind
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind
import java.time.LocalDate
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
        remoteFileId = FILE_ID,
        dayId = DAY_ID,
        day = LocalDate.ofEpochDay(DAY_ID.toLong()),
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
        remoteFileId = null,
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
        )
    }
}
