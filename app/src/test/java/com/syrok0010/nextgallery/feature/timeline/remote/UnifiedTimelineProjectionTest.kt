package com.syrok0010.nextgallery.feature.timeline.remote

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.app.library.TimelineSnapshotProjection
import com.syrok0010.nextgallery.core.media.hasRemoteCopy
import com.syrok0010.nextgallery.feature.timeline.MemoriesConfig
import com.syrok0010.nextgallery.feature.timeline.TimelineDay
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshotAssembler
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
        val item = requireNotNull(project(listOf(local), remoteSnapshot)).items.single()

        assertEquals(MediaId("local-published"), item.mediaId)
        assertEquals(remote.displayName, item.displayName)
        assertEquals(remote.dayId, item.dayId)
        assertEquals(remote.takenAtEpochSeconds, item.takenAtEpochSeconds)
        assertEquals(remote.etag, item.etag)
        val assets = item.assetRef as MediaAssetRef.LocalFirst
        assertEquals(local.assetRef, assets.local)
        assertEquals(remote.assetRef, assets.remote)
        assertTrue(item.hasRemoteCopy)
    }

    @Test
    fun `local-only and cloud-only copies remain separate media objects with correct cloud presence`() = runBlocking {
        val local = localItem(MediaId("local-only"), auid = "local-auid")
        val remote = remoteItem(MediaId("cloud-only"), auid = "cloud-auid")
        val projectedItems = requireNotNull(project(listOf(local), remoteSnapshot(remote))).items

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

        val snapshot = requireNotNull(project(listOf(local), remoteSnapshot(remote)))

        assertEquals(listOf(MediaId("local-published")), snapshot.items.map { it.mediaId })
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
        val snapshot = requireNotNull(project(listOf(first, second), remoteSnapshot))

        assertEquals(3, snapshot.items.size)
        assertEquals(MediaId("remote-separate"), snapshot.items.first().mediaId)
        assertEquals(
            setOf(MediaId("local-first"), MediaId("local-second")),
            snapshot.items.drop(1).mapTo(mutableSetOf()) { it.mediaId },
        )
    }

    @Test
    fun `day details loaded later join the local copy already on screen`() = runBlocking {
        val local = localItem(mediaId = MediaId("local-published"), auid = "shared-auid")
        val remote = remoteItem(mediaId = MediaId("remote-generated"), auid = "shared-auid")
        val remoteIndex = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(remote.dayId, 1)),
        )

        assertEquals(listOf(local.mediaId), requireNotNull(project(listOf(local), null)).items.map { it.mediaId })
        assertEquals(listOf(local.mediaId), requireNotNull(project(listOf(local), remoteIndex)).items.map { it.mediaId })

        val withDetails = TimelineSnapshotAssembler.mergeLoadedItems(remoteIndex, listOf(remote), setOf(remote.dayId))
        val merged = requireNotNull(project(listOf(local), withDetails))

        val item = merged.items.single()
        assertEquals(local.mediaId, item.mediaId)
        assertEquals(local.assetRef, (item.assetRef as MediaAssetRef.LocalFirst).local)
        assertEquals(setOf(remote.dayId), merged.loadedDayIds)
    }

    @Test
    fun `without the phone copy a merged object stays in the timeline as cloud-only`() = runBlocking {
        val mergedLocal = localItem(
            mediaId = MediaId("local-published"),
            auid = "shared-auid",
        )
        val localOnly = localItem(
            mediaId = MediaId("local-only"),
            auid = "local-only-auid",
            buid = "local-only-buid",
            contentUri = "content://images/7",
        )
        // After the merge the identity registry resolves the server copy to the phone file's
        // MediaId, and keeps doing so when the phone copy disappears (RoomMediaIdentityRegistryTest).
        val remote = remoteItem(mediaId = mergedLocal.mediaId, auid = "shared-auid")

        val withPermission = requireNotNull(project(listOf(mergedLocal, localOnly), remoteSnapshot(remote)))
        assertTrue(withPermission.items.first { it.mediaId == mergedLocal.mediaId }.assetRef is MediaAssetRef.LocalFirst)
        assertTrue(withPermission.items.any { it.mediaId == localOnly.mediaId })

        val withoutPermission = requireNotNull(project(emptyList(), remoteSnapshot(remote)))
        val cloudOnly = withoutPermission.items.single()
        assertEquals(mergedLocal.mediaId, cloudOnly.mediaId)
        assertTrue(cloudOnly.assetRef is MediaAssetRef.MemoriesFile)
    }

    @Test
    fun `local item does not mark unloaded remote day as loaded but marks local-only day as loaded`() = runBlocking {
        val sameDayLocal = localItem(
            mediaId = MediaId("local-same-day"),
            auid = "local-same-day-auid",
            buid = "local-buid-1",
            contentUri = "content://images/100",
        ).copy(dayId = 100)
        val localOnlyDayItem = localItem(
            mediaId = MediaId("local-only-day"),
            auid = "local-only-day-auid",
            buid = "local-buid-2",
            contentUri = "content://images/200",
        ).copy(dayId = 200)

        val remoteLoadedItem = remoteItem(
            mediaId = MediaId("remote-loaded"),
            auid = "remote-loaded-auid",
        ).copy(dayId = 300)
        val remoteSnapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(
                TimelineDay(dayId = 300, count = 1),
                TimelineDay(dayId = 100, count = 2),
            ),
            mediaItems = listOf(remoteLoadedItem),
            loadedDayIds = setOf(300),
        )

        val snapshot = requireNotNull(project(listOf(sameDayLocal, localOnlyDayItem), remoteSnapshot))

        assertEquals(setOf(200, 300), snapshot.loadedDayIds)
    }

    /** What the timeline shows: the canonical library laid over the remote day index. */
    private suspend fun project(local: List<MediaItem>, remote: TimelineSnapshot?): TimelineSnapshot? =
        TimelineSnapshotProjection.project(remote, projectMediaLibrary(local, remote?.items.orEmpty()))

    private fun localItem(
        mediaId: MediaId,
        auid: String,
        buid: String = "local-buid",
        contentUri: String = "content://images/42",
    ) = MediaItem(
        mediaId = mediaId,
        dayId = 19_869,
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
        assetRef = MediaAssetRef.LocalContent(contentUri, 1_716_900_000, 1),
    )

    private fun remoteItem(
        mediaId: MediaId,
        auid: String,
        buid: String? = "remote-buid",
    ) = MediaItem(
        mediaId = mediaId,
        dayId = 19_870,
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
        version = "10.1.0",
        timelinePath = "/Photos",
        albumsEnabled = false,
        recognizeEnabled = false,
        faceRecognitionEnabled = false,
        previewGeneratorEnabled = false,
        stackRawFiles = false,
        dedupIdentical = false,
    )
}
