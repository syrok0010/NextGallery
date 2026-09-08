package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.local.LocalMediaProjectionRepository
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaAlias
import com.syrok0010.nextgallery.data.memories.MediaAliasKind
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.ThumbnailPreview
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.thumbnail.ThumbnailKey
import com.syrok0010.nextgallery.domain.media.MediaId
import com.syrok0010.nextgallery.domain.media.MediaSourceIdentity
import com.syrok0010.nextgallery.domain.media.MediaSourceKind
import java.time.LocalDate
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
    private lateinit var repository: TimelineCacheRepository
    private lateinit var thumbnailFileStore: ThumbnailFileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
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
    fun cachedSnapshotContainsOnlyMaterializedCloudObjects() = runBlocking {
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
    fun clearingCloudCachePreservesDeviceProjectionAndReusableIdentity() = runBlocking {
        val sharedAlias = MediaAlias(MediaAliasKind.Auid, "shared-auid")
        val local = localItem()
        identityRegistry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = local.sourceIdentity(),
                    publishedMediaId = local.mediaId,
                    aliases = setOf(sharedAlias),
                ),
            ),
        )
        LocalMediaProjectionRepository(database).saveLocalMediaBatch(listOf(local))

        val remote = remoteItem().copy(mediaId = local.mediaId, auid = sharedAlias.value)
        identityRegistry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = remote.sourceIdentity(),
                    publishedMediaId = MediaId("remote-before-logout"),
                    aliases = setOf(sharedAlias),
                ),
                MediaIdentityCandidate(
                    source = MediaSourceIdentity(MediaSourceKind.Memories, REMOTE_ONLY_FILE_ID.toString()),
                    publishedMediaId = MediaId("remote-only-before-logout"),
                    aliases = setOf(MediaAlias(MediaAliasKind.Auid, "remote-only-auid")),
                ),
            ),
        )
        repository.saveTimelineSnapshot(
            credentials = credentials,
            snapshot = TimelineSnapshotAssembler.assemble(
                config = memoriesConfig(),
                days = listOf(TimelineDay(MATERIALIZED_DAY_ID, 1)),
                mediaItems = listOf(remote),
                loadedDayIds = setOf(MATERIALIZED_DAY_ID),
            ),
        )
        repository.saveThumbnails(
            previews = listOf(ThumbnailPreview(FILE_ID, 0, "image/jpeg", byteArrayOf(1, 2, 3))),
            width = THUMBNAIL_SIZE,
            height = THUMBNAIL_SIZE,
            etagsByFileId = mapOf(FILE_ID to remote.etag),
            accountScope = ACCOUNT_SCOPE,
        )
        assertEquals(listOf(thumbnailKey(remote)), loadThumbnailKeys(remote))

        repository.clear()

        assertEquals(null, repository.loadTimelineSnapshot(credentials))
        val restoredLocal = LocalMediaProjectionRepository(database)
            .loadLocalMediaProjection()
            .single()
        assertEquals(local.mediaId, restoredLocal.mediaId)
        assertEquals(local.auid, restoredLocal.auid)
        assertEquals(local.buid, restoredLocal.buid)
        assertEquals(LOCAL_CONTENT_URI, (restoredLocal.assetRef as MediaAssetRef.LocalContent).contentUri)
        assertEquals(emptyList<ThumbnailKey>(), loadThumbnailKeys(remote))

        val reloggedShared = identityRegistry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = remote.sourceIdentity(),
                    publishedMediaId = MediaId("remote-after-login"),
                    aliases = setOf(sharedAlias),
                ),
            ),
        )
        assertEquals(local.mediaId, reloggedShared.mediaIds.getValue(remote.sourceIdentity()))

        val remoteOnlySource = MediaSourceIdentity(MediaSourceKind.Memories, REMOTE_ONLY_FILE_ID.toString())
        val reloggedRemoteOnly = identityRegistry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = remoteOnlySource,
                    publishedMediaId = MediaId("remote-only-after-login"),
                    aliases = setOf(MediaAlias(MediaAliasKind.Auid, "remote-only-auid")),
                ),
            ),
        )
        assertEquals(MediaId("remote-only-after-login"), reloggedRemoteOnly.mediaIds.getValue(remoteOnlySource))
    }

    private fun remoteItem() = MediaItem(
        mediaId = MediaId("remote-42"),
        remoteFileId = FILE_ID,
        dayId = MATERIALIZED_DAY_ID,
        day = LocalDate.ofEpochDay(MATERIALIZED_DAY_ID.toLong()),
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

    private fun localItem() = remoteItem().copy(
        mediaId = MediaId("local-42"),
        remoteFileId = null,
        auid = "shared-auid",
        buid = "local-buid-42",
        assetRef = MediaAssetRef.LocalContent(
            contentUri = LOCAL_CONTENT_URI,
            modifiedAtEpochSeconds = 1_717_100_000L,
        ),
    )

    private fun MediaItem.sourceIdentity(): MediaSourceIdentity = when (val asset = assetRef) {
        is MediaAssetRef.MemoriesFile -> MediaSourceIdentity(MediaSourceKind.Memories, asset.photoFileId.toString())
        is MediaAssetRef.LocalContent -> MediaSourceIdentity(MediaSourceKind.Local, asset.contentUri)
        is MediaAssetRef.LocalFirst -> error("Cache integration test expects source copies")
    }

    private suspend fun loadThumbnailKeys(item: MediaItem) = repository.loadThumbnailKeys(
        fileIds = listOf(FILE_ID),
        width = THUMBNAIL_SIZE,
        height = THUMBNAIL_SIZE,
        etagsByFileId = mapOf(FILE_ID to item.etag),
        accountScope = ACCOUNT_SCOPE,
    )

    private fun thumbnailKey(item: MediaItem) = ThumbnailKey(
        accountScope = ACCOUNT_SCOPE,
        fileId = FILE_ID,
        width = THUMBNAIL_SIZE,
        height = THUMBNAIL_SIZE,
        etag = item.etag,
    )

    private fun memoriesConfig() = MemoriesConfig(
        version = "8.1.0",
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
        const val REMOTE_ONLY_FILE_ID = 43L
        const val LOCAL_CONTENT_URI = "content://media/external/images/media/42"
        const val THUMBNAIL_SIZE = 512
        const val ACCOUNT_SCOPE = "https://cloud.example.test|test-user"

        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.test",
            loginName = "test-user",
            appPassword = "test-password",
        )
    }
}
