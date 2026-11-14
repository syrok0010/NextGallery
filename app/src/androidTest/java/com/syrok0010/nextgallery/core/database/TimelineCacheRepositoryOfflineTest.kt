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
import com.syrok0010.nextgallery.feature.images.ThumbnailFileStore
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
            val withoutAliases = remoteItem().copy(auid = null, buid = null)
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
                width = THUMBNAIL_SIZE,
                height = THUMBNAIL_SIZE,
                etagsByFileId = listOf(
                    changed,
                    unchanged,
                ).associate { it.remoteFileId!! to it.etag },
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
                fileIds = items.map { it.remoteFileId!! },
                width = THUMBNAIL_SIZE,
                height = THUMBNAIL_SIZE,
                etagsByFileId = items.associate { it.remoteFileId!! to it.etag },
            ).map { it.fileId }

    private fun memoriesConfig() =
        MemoriesConfig(
            version = "10.1.0",
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
