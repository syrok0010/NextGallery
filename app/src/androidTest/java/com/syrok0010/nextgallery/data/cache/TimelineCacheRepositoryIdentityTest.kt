package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TimelineCacheRepositoryIdentityTest {
    private lateinit var context: Context
    private lateinit var database: TimelineCacheDatabase
    private lateinit var repository: TimelineCacheRepository

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
    fun localContentUriKeepsMediaIdAcrossRepositoryInstances() = runBlocking {
        val first = repository.resolveLocalMediaIds(listOf(LOCAL_CONTENT_URI))

        database.close()
        openRepository(mediaIdFactory = { error("Existing local identity must not generate a new MediaId") })

        val second = repository.resolveLocalMediaIds(listOf(LOCAL_CONTENT_URI))

        assertEquals(first[LOCAL_CONTENT_URI], second[LOCAL_CONTENT_URI])
    }

    @Test
    fun remoteMediaIdSurvivesDatabaseReopenAndNetworkRefresh() = runBlocking {
        val firstMediaId = repository.resolveRemoteMediaIds(
            fileIds = listOf(FILE_ID),
        ).getValue(FILE_ID)
        repository.saveTimelineSnapshot(credentials, snapshot(mediaItem(firstMediaId)))

        database.close()
        openRepository(mediaIdFactory = { error("Existing identity must not generate a new MediaId") })

        val refreshedMediaId = repository.resolveRemoteMediaIds(
            fileIds = listOf(FILE_ID),
        ).getValue(FILE_ID)

        assertEquals(MediaId("first-persistent-id"), firstMediaId)
        assertEquals(firstMediaId, refreshedMediaId)

        repository.saveTimelineSnapshot(credentials, snapshot(mediaItem(refreshedMediaId)))

        assertEquals(
            MediaId("first-persistent-id"),
            repository.loadTimelineSnapshot(credentials)?.items?.single()?.mediaId,
        )
    }

    private fun openRepository(
        mediaIdFactory: () -> MediaId = { MediaId("first-persistent-id") },
    ) {
        database = Room.databaseBuilder(
            context,
            TimelineCacheDatabase::class.java,
            DATABASE_NAME,
        ).build()
        repository = TimelineCacheRepository(
            database = database,
            thumbnailFileStore = ThumbnailFileStore(context),
            mediaIdFactory = mediaIdFactory,
        )
    }

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
        fileId = FILE_ID,
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

    private companion object {
        const val LOCAL_CONTENT_URI = "content://media/external/images/media/42"
        const val DATABASE_NAME = "timeline-cache-identity-test.db"
        const val DAY_ID = 19_870
        const val FILE_ID = 42L

        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.test",
            loginName = "test-user",
            appPassword = "test-password",
        )
    }
}
