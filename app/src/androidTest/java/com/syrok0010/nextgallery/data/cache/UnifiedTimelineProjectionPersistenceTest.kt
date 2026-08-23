package com.syrok0010.nextgallery.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.data.memories.MediaAlias
import com.syrok0010.nextgallery.data.memories.MediaAliasKind
import com.syrok0010.nextgallery.data.memories.MediaIdentityCandidate
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.MemoriesConfig
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSnapshotAssembler
import com.syrok0010.nextgallery.data.memories.UnifiedTimelineProjection
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
class UnifiedTimelineProjectionPersistenceTest {
    private lateinit var context: Context
    private lateinit var database: NextGalleryDatabase

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
    fun mergedAliasesAndLocalMediaIdSurviveDatabaseReopen() = runBlocking {
        val local = item(
            mediaId = MediaId("published-local"),
            assetRef = MediaAssetRef.LocalContent("content://images/42", 1_700_000_000),
        )
        RoomMediaIdentityRegistry(database).resolve(listOf(local.identityCandidate()))

        database.close()
        openDatabase()

        val remote = item(
            mediaId = MediaId("generated-remote"),
            assetRef = MediaAssetRef.MemoriesFile(42),
        )
        val registry = RoomMediaIdentityRegistry(database)
        val resolvedRemote = registry.resolve(listOf(remote.identityCandidate()))
        val identifiedRemote = remote.copy(mediaId = resolvedRemote.mediaIds.getValue(remote.sourceIdentity()))
        val afterRestart = UnifiedTimelineProjection()
            .replaceRemoteSnapshot(remoteSnapshot(identifiedRemote))

        assertEquals(MediaId("published-local"), requireNotNull(afterRestart.snapshot).items.single().mediaId)
    }

    @Test
    fun aliasConflictSurvivesDatabaseReopenForDiagnostics() = runBlocking {
        val first = item(
            mediaId = MediaId("local-first"),
            assetRef = MediaAssetRef.LocalContent("content://images/1", 1_700_000_000),
            auid = "first-auid",
            buid = "first-buid",
        )
        val second = item(
            mediaId = MediaId("local-second"),
            assetRef = MediaAssetRef.LocalContent("content://images/2", 1_700_000_000),
            auid = "second-auid",
            buid = "second-buid",
        )
        val remote = item(
            mediaId = MediaId("remote-separate"),
            assetRef = MediaAssetRef.MemoriesFile(42),
            auid = "first-auid",
            buid = "second-buid",
        )
        val registry = RoomMediaIdentityRegistry(database)
        registry.resolve(listOf(first.identityCandidate(), second.identityCandidate()))
        registry.resolve(listOf(remote.identityCandidate()))

        database.close()
        openDatabase()

        assertEquals(
            setOf(MediaId("local-first"), MediaId("local-second")),
            RoomMediaIdentityRegistry(database).conflicts().single().conflictingMediaIds,
        )
    }

    private fun openDatabase() {
        database = Room.databaseBuilder(context, NextGalleryDatabase::class.java, DATABASE_NAME).build()
    }

    private fun remoteSnapshot(item: MediaItem) = TimelineSnapshotAssembler.assemble(
        config = MemoriesConfig("8.0.1", "/Photos", false, false, false, false, false, false),
        days = listOf(TimelineDay(item.dayId, 1)),
        mediaItems = listOf(item),
        loadedDayIds = setOf(item.dayId),
    )

    private fun item(
        mediaId: MediaId,
        assetRef: MediaAssetRef,
        auid: String = "shared-auid",
        buid: String = "shared-buid",
    ) = MediaItem(
        mediaId = mediaId,
        remoteFileId = (assetRef as? MediaAssetRef.MemoriesFile)?.photoFileId,
        dayId = 19_675,
        day = LocalDate.ofEpochDay(19_675),
        displayName = "IMG_0042.jpg",
        mimeType = "image/jpeg",
        width = 4_032,
        height = 3_024,
        etag = null,
        livePhotoId = null,
        auid = auid,
        buid = buid,
        sharedBy = null,
        takenAtEpochSeconds = 1_700_000_000,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = assetRef,
    )

    private fun MediaItem.identityCandidate() = MediaIdentityCandidate(
        source = sourceIdentity(),
        publishedMediaId = mediaId,
        aliases = buildSet {
            auid?.let { add(MediaAlias(MediaAliasKind.Auid, it)) }
            buid?.let { add(MediaAlias(MediaAliasKind.Buid, it)) }
        },
    )

    private fun MediaItem.sourceIdentity(): MediaSourceIdentity = when (val asset = assetRef) {
        is MediaAssetRef.LocalContent -> MediaSourceIdentity(MediaSourceKind.Local, asset.contentUri)
        is MediaAssetRef.MemoriesFile -> MediaSourceIdentity(MediaSourceKind.Memories, asset.photoFileId.toString())
        is MediaAssetRef.LocalFirst -> error("Persistence test expects source copies")
    }

    private companion object {
        const val DATABASE_NAME = "unified-timeline-projection-test.db"
    }
}
