package com.syrok0010.nextgallery.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaIdentityCandidate
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.MediaSourceIdentity
import com.syrok0010.nextgallery.core.media.MediaSourceKind
import com.syrok0010.nextgallery.core.network.memories.MemoriesPreviewDto
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.feature.images.RemoteImageCache
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
import com.syrok0010.nextgallery.feature.images.ThumbnailKey
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.feature.timeline.persistence.TimelineCacheRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineCacheRepositoryOfflineTest {
    private lateinit var context: Context
    private lateinit var database: NextGalleryDatabase
    private lateinit var identityRegistry: RoomMediaIdentityRegistry
    private val imageCache get() = com.syrok0010.nextgallery.feature.images.RemoteImageCache(
        database,
        thumbnailFileStore,
    )
    private lateinit var repository: TimelineCacheRepository
    private lateinit var thumbnailFileStore: ThumbnailFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room
            .databaseBuilder(
                context,
                NextGalleryDatabase::class.java,
                DATABASE_NAME,
            ).build()
        identityRegistry = RoomMediaIdentityRegistry(database) { MediaId("remote-42") }
        thumbnailFileStore = ThumbnailFileStore(context)
        repository = TimelineCacheRepository(
            database = database,
            thumbnailFileStore = thumbnailFileStore,
            identityRegistry = identityRegistry,
        )
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun albumFavoriteReadUpdatesCacheWithoutMaterializingUncachedAlbumMembers() = runBlocking {
        val item = remoteItem()
        identityRegistry.resolve(listOf(MediaIdentityCandidate(
            source = item.sourceIdentity(), publishedMediaId = item.mediaId, aliases = emptySet(),
        )))
        repository.saveTimelineSnapshot(credentials, TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(), days = listOf(TimelineDay(item.dayId, 1)),
            mediaItems = listOf(item), loadedDayIds = setOf(item.dayId),
        ))
        repository.updateCachedFavorites(listOf(
            item.copy(isFavorite = true),
            item.copy(mediaId = MediaId("album-only"), assetRef = MediaAssetRef.MemoriesFile(99)),
        ))
        val restored = requireNotNull(repository.loadTimelineSnapshot(credentials))
        assertEquals(listOf(item.copy(isFavorite = true)), restored.items)
        assertEquals(setOf(item.dayId), restored.loadedDayIds)
    }

    @Test
    fun freshIndexRemovesDeletedDayAfterFavoriteConfirmationAndDatabaseReopen() = runBlocking {
        val item = remoteItem()
        identityRegistry.resolve(listOf(MediaIdentityCandidate(
            source = item.sourceIdentity(), publishedMediaId = item.mediaId, aliases = emptySet(),
        )))
        repository.saveTimelineSnapshot(credentials, TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(), days = listOf(TimelineDay(item.dayId, 1)),
            mediaItems = listOf(item), loadedDayIds = setOf(item.dayId),
        ))
        val sessions = com.syrok0010.nextgallery.core.session.SessionStore(object : com.syrok0010.nextgallery.core.session.CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
        })
        val favorites = com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata(sessions)
        favorites.confirm(favorites.begin(credentials), item, true) { repository.saveDayDetails(listOf(it), emptySet()) }
        // This read starts after confirmation. The only photo has since been deleted on the server.
        val fresh = favorites.begin(credentials)
        favorites.ingest(fresh, emptyList()) {
            repository.saveTimelineSnapshot(credentials, TimelineSnapshotAssembler.assemble(
                config = memoriesConfig(), days = emptyList(),
            ))
        }
        database.close()
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
        identityRegistry = RoomMediaIdentityRegistry(database) { MediaId("unused") }
        repository = TimelineCacheRepository(database, thumbnailFileStore, identityRegistry)
        val restored = requireNotNull(repository.loadTimelineSnapshot(credentials))
        org.junit.Assert.assertTrue(restored.items.isEmpty())
        org.junit.Assert.assertTrue(restored.days.isEmpty())
    }

    @Test
    fun confirmedFavoriteSurvivesStaleRefreshInvalidationAndDatabaseReopen() = runBlocking {
        val item = remoteItem().copy(auid = null, buid = null)
        identityRegistry.resolve(listOf(MediaIdentityCandidate(
            source = item.sourceIdentity(), publishedMediaId = item.mediaId, aliases = emptySet(),
        )))
        repository.saveTimelineSnapshot(credentials, TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(), days = listOf(TimelineDay(item.dayId, 1)),
            mediaItems = listOf(item), loadedDayIds = setOf(item.dayId),
        ))
        val sessions = com.syrok0010.nextgallery.core.session.SessionStore(object : com.syrok0010.nextgallery.core.session.CredentialsStore {
            override fun load() = credentials
            override fun save(credentials: AccountCredentials) = Unit
        })
        val favorites = com.syrok0010.nextgallery.feature.library.CloudFavoriteMetadata(sessions)
        val read = favorites.begin(credentials)
        favorites.confirm(read, item, true) { repository.saveDayDetails(listOf(it), emptySet()) }
        favorites.ingest(read, listOf(item.copy(isFavorite = false))) { repository.saveDayDetails(it, emptySet()) }
        // A late index response invalidates this day's count but has no details for the old photo.
        favorites.ingest(read, emptyList()) {
            repository.saveTimelineSnapshot(credentials, TimelineSnapshotAssembler.assemble(
                config = memoriesConfig(), days = listOf(TimelineDay(item.dayId, 2)),
            ))
        }
        database.close()
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
        identityRegistry = RoomMediaIdentityRegistry(database) { MediaId("unused") }
        repository = TimelineCacheRepository(database, thumbnailFileStore, identityRegistry)
        val restored = database.memoriesTimelineDao().mediaItems().single()
        org.junit.Assert.assertTrue(restored.media.isFavorite)
        assertEquals(item.mediaId.value, restored.mediaId)
        org.junit.Assert.assertTrue(requireNotNull(repository.loadTimelineSnapshot(credentials)).items.isEmpty())
        // A later authoritative item read can remove a star made in another client from warm cache.
        repository.saveDayDetails(listOf(item.copy(isFavorite = false)), emptySet())
        assertFalse(database.memoriesTimelineDao().mediaItems().single().media.isFavorite)
    }

    @Test
    fun cachedSnapshotContainsOnlyMaterializedCloudObjects() =
        runBlocking {
            val materialized = remoteItem()
            identityRegistry.resolve(
                listOf(
                    MediaIdentityCandidate(
                        source = materialized.sourceIdentity(),
                        publishedMediaId = materialized.mediaId,
                        aliases = emptySet(),
                    ),
                ),
            )
            repository.saveTimelineSnapshot(
                credentials = credentials,
                snapshot = TimelineSnapshotAssembler.assemble(
                    config = memoriesConfig(),
                    days = listOf(
                        TimelineDay(dayId = MATERIALIZED_DAY_ID, count = 2),
                        TimelineDay(dayId = INDEX_ONLY_DAY_ID, count = 1),
                    ),
                    mediaItems = listOf(materialized),
                    loadedDayIds = setOf(MATERIALIZED_DAY_ID),
                ),
            )

            val cached = repository.loadTimelineSnapshot(credentials)

            assertNotNull(cached)
            requireNotNull(cached)
            assertEquals(listOf(materialized), cached.items)
            assertEquals(listOf(TimelineDay(MATERIALIZED_DAY_ID, 1)), cached.days)
            assertEquals(1, cached.slots.size)
            assertFalse(cached.slots.any { it.mediaItem == null })
            assertEquals(setOf(MATERIALIZED_DAY_ID), cached.loadedDayIds)
            assertEquals(1, cached.totalMediaCountHint)
        }

    @Test
    fun cloudObjectWithoutAliasesSurvivesOfflineRestore() =
        runBlocking {
            val withoutAliases = remoteItem().copy(
                auid = null,
                buid = null,
                indexedAtEpochSeconds = 100,
            )
            identityRegistry.resolve(
                listOf(
                    MediaIdentityCandidate(
                        source = withoutAliases.sourceIdentity(),
                        publishedMediaId = withoutAliases.mediaId,
                        aliases = emptySet(),
                    ),
                ),
            )
            repository.saveTimelineSnapshot(
                credentials = credentials,
                snapshot = TimelineSnapshotAssembler.assemble(
                    config = memoriesConfig(),
                    days = listOf(TimelineDay(dayId = MATERIALIZED_DAY_ID, count = 1)),
                    mediaItems = listOf(withoutAliases),
                    loadedDayIds = setOf(MATERIALIZED_DAY_ID),
                ),
            )

            assertEquals(
                listOf(withoutAliases),
                repository.loadTimelineSnapshot(credentials)?.items,
            )
        }

    @Test
    fun dayWhoseCountChangedLosesItsLoadedMarkAndThumbnails() =
        runBlocking {
            val changed = remoteItem()
            val unchanged = remoteItem().copy(
                mediaId = MediaId("remote-43"),
                dayId = INDEX_ONLY_DAY_ID,
                etag = "etag-43",
                auid = "auid-43",
                buid = "buid-43",
                assetRef = MediaAssetRef.MemoriesFile(OTHER_FILE_ID),
            )
            identityRegistry.resolve(
                listOf(changed, unchanged).map {
                    MediaIdentityCandidate(
                        source = it.sourceIdentity(),
                        publishedMediaId = it.mediaId,
                        aliases = emptySet(),
                    )
                },
            )
            repository.saveTimelineSnapshot(
                credentials,
                TimelineSnapshotAssembler.assemble(
                    config = memoriesConfig(),
                    days = listOf(TimelineDay(changed.dayId, 1), TimelineDay(unchanged.dayId, 1)),
                    mediaItems = listOf(changed, unchanged),
                    loadedDayIds = setOf(changed.dayId, unchanged.dayId),
                ),
            )
            imageCache.saveThumbnails(
                previews = listOf(changed, unchanged).map {
                    MemoriesPreviewDto(it.remoteFileId!!, "image/jpeg", byteArrayOf(1))
                },
                keys = listOf(changed, unchanged).map { it.thumbnailKey() },
            )
            assertEquals(
                listOf(FILE_ID, OTHER_FILE_ID),
                cachedThumbnails(changed, unchanged).sorted(),
            )

            // The server now counts one more photo on the first day; its details are not loaded yet.
            repository.saveTimelineSnapshot(
                credentials,
                TimelineSnapshotAssembler.assemble(
                    config = memoriesConfig(),
                    days = listOf(TimelineDay(changed.dayId, 2), TimelineDay(unchanged.dayId, 1)),
                ),
            )

            assertEquals(
                setOf(unchanged.dayId),
                repository.loadTimelineSnapshot(credentials)?.loadedDayIds,
            )
            assertEquals(listOf(OTHER_FILE_ID), cachedThumbnails(changed, unchanged))
        }

    private fun remoteItem() =
        MediaItem(
            mediaId = MediaId("remote-42"),
            dayId = MATERIALIZED_DAY_ID,
            displayName = "IMG_0042.jpg",
            mimeType = "image/jpeg",
            width = 4_032,
            height = 3_024,
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
            assetRef = MediaAssetRef.MemoriesFile(FILE_ID),
        )

    @Test
    fun reindexingInvalidatesDiskThumbnailWithoutChangingEtag() =
        runBlocking {
            val old = remoteItem().copy(indexedAtEpochSeconds = 100).thumbnailKey()
            val reindexed = old.copy(indexedAtEpochSeconds = 200)
            imageCache.saveThumbnails(
                listOf(MemoriesPreviewDto(old.fileId, "image/jpeg", byteArrayOf(1))),
                listOf(old),
            )
            assertEquals(listOf(old), imageCache.loadThumbnailKeys(listOf(old)))

            // A new repository instance models restoring the persisted cache after restart.
            val reopened = RemoteImageCache(database, ThumbnailFileStore(context))
            assertEquals(emptyList<ThumbnailKey>(), reopened.loadThumbnailKeys(listOf(reindexed)))
            assertFalse(thumbnailFileStore.fileFor(old).exists())
            reopened.saveThumbnails(
                listOf(MemoriesPreviewDto(reindexed.fileId, "image/jpeg", byteArrayOf(2))),
                listOf(reindexed),
            )
            assertEquals(listOf(reindexed), reopened.loadThumbnailKeys(listOf(reindexed)))
            org.junit.Assert.assertArrayEquals(
                byteArrayOf(2),
                thumbnailFileStore.fileFor(reindexed).readBytes(),
            )
        }

    private fun MediaItem.sourceIdentity(): MediaSourceIdentity =
        when (val asset = assetRef) {
            is MediaAssetRef.MemoriesFile -> MediaSourceIdentity(
                MediaSourceKind.Memories,
                asset.photoFileId.toString(),
            )

            is MediaAssetRef.LocalContent -> MediaSourceIdentity(
                MediaSourceKind.Local,
                asset.contentUri,
            )

            is MediaAssetRef.LocalFirst -> error("Cache integration test expects source copies")
        }

    /** Thumbnails the cache still serves for [items], in their order. */
    private suspend fun cachedThumbnails(vararg items: MediaItem) =
        imageCache
            .loadThumbnailKeys(
                keys = items.map { it.thumbnailKey() },
            ).map { it.fileId }

    private fun MediaItem.thumbnailKey() =
        ThumbnailKey(
            remoteFileId!!,
            THUMBNAIL_SIZE,
            THUMBNAIL_SIZE,
            etag,
            indexedAtEpochSeconds,
        )

    private fun memoriesConfig() =
        MemoriesConfig(
            version = "11.1.4",
            timelinePath = "/Photos",
            albumsEnabled = false,
            recognizeEnabled = false,
            faceRecognitionEnabled = false,
            previewGeneratorEnabled = false,
            stackRawFiles = false,
            dedupIdentical = false,
        )

    private companion object {
        const val DATABASE_NAME = "timeline-cache-offline-test.db"
        const val MATERIALIZED_DAY_ID = 19_870
        const val INDEX_ONLY_DAY_ID = 19_869
        const val FILE_ID = 42L
        const val OTHER_FILE_ID = 43L
        const val THUMBNAIL_SIZE = 512

        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.test",
            loginName = "test-user",
            appPassword = "test-password",
            userId = "test-user",
        )
    }
}
