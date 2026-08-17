package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaItem
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
class LocalMediaProjectionRepositoryTest {
    private lateinit var context: Context
    private lateinit var database: NextGalleryDatabase
    private lateinit var cacheRepository: TimelineCacheRepository
    private lateinit var identityRegistry: RoomMediaIdentityRegistry
    private lateinit var repository: LocalMediaProjectionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
        openRepository()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun localProjectionSurvivesRestartAndReconciliation() = runBlocking {
        val newest = localItem(
            contentUri = "content://media/external/images/media/42",
            mediaId = MediaId("persistent-42"),
            modifiedAtEpochSeconds = 1_700_000_042L,
        )
        val removed = localItem(
            contentUri = "content://media/external/video/media/7",
            mediaId = MediaId("persistent-7"),
            modifiedAtEpochSeconds = 1_700_000_007L,
        )
        registerLocalIdentity(newest)
        registerLocalIdentity(removed)
        repository.saveLocalMediaBatch(listOf(newest, removed))

        database.close()
        openRepository()

        assertEquals(listOf(newest, removed), repository.loadLocalMediaProjection())

        repository.finishLocalMediaReconciliation(setOf(newest.localContentUri()))

        assertEquals(listOf(newest), repository.loadLocalMediaProjection())
    }

    @Test
    fun clearingCloudCacheKeepsDeviceProjectionAndIdentity() = runBlocking {
        val contentUri = "content://media/external/images/media/42"
        val persistentMediaId = resolveLocalMediaId(contentUri)
        val item = localItem(
            contentUri = contentUri,
            mediaId = persistentMediaId,
            modifiedAtEpochSeconds = 1_700_000_042L,
        )
        repository.saveLocalMediaBatch(listOf(item))

        cacheRepository.clear()

        assertEquals(listOf(item), repository.loadLocalMediaProjection())
        assertEquals(
            persistentMediaId,
            resolveLocalMediaId(item.localContentUri()),
        )
    }

    private fun openRepository() {
        database = Room.databaseBuilder(
            context,
            NextGalleryDatabase::class.java,
            DATABASE_NAME,
        ).build()
        identityRegistry = RoomMediaIdentityRegistry(
            database = database,
            mediaIdFactory = { MediaId("unexpected-new-id") },
        )
        cacheRepository = TimelineCacheRepository(
            database = database,
            thumbnailFileStore = ThumbnailFileStore(context),
            identityRegistry = identityRegistry,
        )
        repository = LocalMediaProjectionRepository(database)
    }

    private suspend fun registerLocalIdentity(item: MediaItem) {
        identityRegistry.resolve(
            listOf(
                MediaIdentityCandidate(
                    source = item.localContentUri().localSourceIdentity(),
                    publishedMediaId = item.mediaId,
                    aliases = emptySet(),
                ),
            ),
        )
    }

    private suspend fun resolveLocalMediaId(contentUri: String): MediaId = identityRegistry.resolve(
        listOf(
            MediaIdentityCandidate(
                source = contentUri.localSourceIdentity(),
                aliases = emptySet(),
            ),
        ),
    ).mediaIds.getValue(contentUri.localSourceIdentity())

    private fun String.localSourceIdentity() = MediaSourceIdentity(MediaSourceKind.Local, this)

    private fun localItem(
        contentUri: String,
        mediaId: MediaId,
        modifiedAtEpochSeconds: Long,
    ): MediaItem {
        val dayId = Math.floorDiv(modifiedAtEpochSeconds, 86_400L).toInt()
        return MediaItem(
            mediaId = mediaId,
            remoteFileId = null,
            dayId = dayId,
            day = LocalDate.ofEpochDay(dayId.toLong()),
            displayName = contentUri.substringAfterLast('/'),
            mimeType = if ("video" in contentUri) "video/mp4" else "image/jpeg",
            width = 4_032,
            height = 3_024,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = modifiedAtEpochSeconds,
            isVideo = "video" in contentUri,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = contentUri,
                modifiedAtEpochSeconds = modifiedAtEpochSeconds,
            ),
        )
    }

    private fun MediaItem.localContentUri(): String =
        (assetRef as MediaAssetRef.LocalContent).contentUri

    private companion object {
        const val DATABASE_NAME = "local-media-projection-test.db"
    }
}
