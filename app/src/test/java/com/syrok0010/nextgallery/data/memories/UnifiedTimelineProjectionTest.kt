package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedTimelineProjectionTest {
    @Test
    fun `shared alias publishes one item with local identity bytes and Memories metadata`() = runBlocking {
        val local = localItem(mediaId = MediaId("local-published"), auid = "shared-auid")
        val remote = remoteItem(mediaId = MediaId("remote-generated"), auid = "shared-auid")
        val remoteSnapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(remote.dayId, 1)),
            mediaItems = listOf(remote),
            loadedDayIds = setOf(remote.dayId),
        )
        val projection = UnifiedTimelineProjection(InMemoryMediaIdentityRegistry())
        projection.replaceLocalItems(listOf(local))

        val result = projection.replaceRemoteSnapshot(remoteSnapshot)

        val item = requireNotNull(result.snapshot).items.single()
        assertEquals(MediaId("local-published"), item.mediaId)
        assertEquals(remote.displayName, item.displayName)
        assertEquals(remote.dayId, item.dayId)
        assertEquals(remote.takenAtEpochSeconds, item.takenAtEpochSeconds)
        val assets = item.assetRef as MediaAssetRef.LocalFirst
        assertEquals(local.assetRef, assets.local)
        assertEquals(remote.assetRef, assets.remote)
        assertTrue(item.hasRemoteCopy)
        assertEquals(emptyList<MediaIdentityConflict>(), result.conflicts)
    }

    @Test
    fun `local-only and cloud-only copies remain separate media objects with correct cloud presence`() = runBlocking {
        val local = localItem(MediaId("local-only"), auid = "local-auid")
        val remote = remoteItem(MediaId("cloud-only"), auid = "cloud-auid")
        val projection = UnifiedTimelineProjection(InMemoryMediaIdentityRegistry())
        projection.replaceLocalItems(listOf(local))

        val result = projection.replaceRemoteSnapshot(remoteSnapshot(remote))

        val projectedItems = requireNotNull(result.snapshot).items
        assertEquals(listOf(MediaId("cloud-only"), MediaId("local-only")), projectedItems.map { it.mediaId })
        val items = projectedItems.associateBy { it.mediaId }
        assertTrue(items.getValue(MediaId("local-only")).assetRef is MediaAssetRef.LocalContent)
        assertEquals(false, items.getValue(MediaId("local-only")).hasRemoteCopy)
        assertTrue(items.getValue(MediaId("cloud-only")).assetRef is MediaAssetRef.MemoriesFile)
        assertTrue(items.getValue(MediaId("cloud-only")).hasRemoteCopy)
    }

    @Test
    fun `matching BUID merges copies when AUIDs differ`() = runBlocking {
        val local = localItem(
            mediaId = MediaId("local-published"),
            auid = "local-auid",
            buid = "shared-buid",
        )
        val remote = remoteItem(
            mediaId = MediaId("remote-generated"),
            auid = "remote-auid",
            buid = "shared-buid",
        )

        val projection = UnifiedTimelineProjection(InMemoryMediaIdentityRegistry())
        projection.replaceLocalItems(listOf(local))

        val result = projection.replaceRemoteSnapshot(remoteSnapshot(remote))

        assertEquals(listOf(MediaId("local-published")), requireNotNull(result.snapshot).items.map { it.mediaId })
    }

    @Test
    fun `aliases pointing to different media IDs keep incoming copy separate and report conflict`() = runBlocking {
        val first = localItem(
            mediaId = MediaId("local-first"),
            auid = "first-auid",
            buid = "first-buid",
            contentUri = "content://images/1",
        )
        val second = localItem(
            mediaId = MediaId("local-second"),
            auid = "second-auid",
            buid = "second-buid",
            contentUri = "content://images/2",
        )
        val conflictingRemote = remoteItem(
            mediaId = MediaId("remote-separate"),
            auid = "first-auid",
            buid = "second-buid",
        )
        val remoteSnapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(conflictingRemote.dayId, 1)),
            mediaItems = listOf(conflictingRemote),
            loadedDayIds = setOf(conflictingRemote.dayId),
        )
        val projection = UnifiedTimelineProjection(InMemoryMediaIdentityRegistry())
        projection.replaceLocalItems(listOf(first, second))

        val result = projection.replaceRemoteSnapshot(remoteSnapshot)

        assertEquals(3, requireNotNull(result.snapshot).items.size)
        assertEquals(MediaId("remote-separate"), result.snapshot.items.first().mediaId)
        assertEquals(
            setOf(MediaId("local-first"), MediaId("local-second")),
            result.conflicts.single().conflictingMediaIds,
        )
    }

    @Test
    fun `conflict detaches a source that was previously merged and keeps the diagnostic ID stable`() = runBlocking {
        val first = localItem(
            mediaId = MediaId("local-first"),
            auid = "first-auid",
            buid = "first-buid",
            contentUri = "content://images/1",
        )
        val second = localItem(
            mediaId = MediaId("local-second"),
            auid = "second-auid",
            buid = "second-buid",
            contentUri = "content://images/2",
        )
        val registry = InMemoryMediaIdentityRegistry { MediaId("conflict-separate") }
        val projection = UnifiedTimelineProjection(registry)
        projection.replaceLocalItems(listOf(first, second))
        projection.replaceRemoteSnapshot(
            remoteSnapshot(remoteItem(MediaId("remote"), "first-auid", buid = null)),
        )
        val conflictingSnapshot = remoteSnapshot(
            remoteItem(MediaId("local-first"), "first-auid", buid = "second-buid"),
        )

        val firstConflict = projection.replaceRemoteSnapshot(conflictingSnapshot)
        val repeatedConflict = projection.replaceRemoteSnapshot(conflictingSnapshot)

        assertEquals(MediaId("conflict-separate"), requireNotNull(firstConflict.snapshot).items.first().mediaId)
        assertEquals(
            setOf(MediaId("local-first"), MediaId("local-second")),
            firstConflict.snapshot.items.drop(1).mapTo(mutableSetOf()) { it.mediaId },
        )
        assertEquals(
            firstConflict.snapshot.items.map { it.mediaId },
            requireNotNull(repeatedConflict.snapshot).items.map { it.mediaId },
        )
    }

    @Test
    fun `sequential source updates retain local media and clear resets the projection`() = runBlocking {
        val local = localItem(mediaId = MediaId("local-published"), auid = "shared-auid")
        val remote = remoteItem(mediaId = MediaId("remote-generated"), auid = "shared-auid")
        val remoteIndex = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(remote.dayId, 1)),
        )
        val projection = UnifiedTimelineProjection(InMemoryMediaIdentityRegistry())

        projection.replaceLocalItems(listOf(local))
        assertEquals(listOf(local.mediaId), requireNotNull(projection.snapshot).items.map { it.mediaId })

        projection.replaceRemoteSnapshot(remoteIndex)
        val merged = projection.mergeRemoteItems(listOf(remote), setOf(remote.dayId))

        val item = requireNotNull(merged.snapshot).items.single()
        assertEquals(local.mediaId, item.mediaId)
        assertEquals(local.assetRef, (item.assetRef as MediaAssetRef.LocalFirst).local)
        assertEquals(setOf(remote.dayId), merged.snapshot.loadedDayIds)

        projection.clear()
        assertEquals(null, projection.snapshot)
    }

    private fun localItem(
        mediaId: MediaId,
        auid: String,
        buid: String = "local-buid",
        contentUri: String = "content://images/42",
    ) = MediaItem(
        mediaId = mediaId,
        remoteFileId = null,
        dayId = 19_869,
        day = LocalDate.ofEpochDay(19_869),
        displayName = "device-name.jpg",
        mimeType = "image/jpeg",
        width = 3_000,
        height = 2_000,
        etag = null,
        livePhotoId = null,
        auid = auid,
        buid = buid,
        sharedBy = null,
        takenAtEpochSeconds = 1_716_900_000,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.LocalContent(contentUri, 1_716_900_000),
    )

    private fun remoteItem(
        mediaId: MediaId,
        auid: String,
        buid: String? = "remote-buid",
    ) = MediaItem(
        mediaId = mediaId,
        remoteFileId = 42,
        dayId = 19_870,
        day = LocalDate.ofEpochDay(19_870),
        displayName = "cloud-name.jpg",
        mimeType = "image/jpeg",
        width = 4_032,
        height = 3_024,
        etag = "etag-42",
        livePhotoId = null,
        auid = auid,
        buid = buid,
        sharedBy = null,
        takenAtEpochSeconds = 1_717_100_000,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = true,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(42),
    )

    private fun remoteSnapshot(item: MediaItem) = TimelineSnapshotAssembler.assemble(
        config = memoriesConfig(),
        days = listOf(TimelineDay(item.dayId, 1)),
        mediaItems = listOf(item),
        loadedDayIds = setOf(item.dayId),
    )

    private fun memoriesConfig() = MemoriesConfig(
        version = "8.0.1",
        timelinePath = "/Photos",
        albumsEnabled = false,
        recognizeEnabled = false,
        faceRecognitionEnabled = false,
        previewGeneratorEnabled = false,
        stackRawFiles = false,
        dedupIdentical = false,
    )
}
