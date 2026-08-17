package com.syrok0010.nextgallery.data.memories

import com.syrok0010.nextgallery.domain.media.MediaId
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimelineSnapshotAssemblerTest {
    @Test
    fun `materialized snapshot excludes index-only slots`() {
        val materialized = mediaItem(fileId = 42, dayId = 19870).copy(
            auid = "auid-42",
            takenAtEpochSeconds = 1_717_100_000L,
        )

        val snapshot = TimelineSnapshotAssembler.assembleMaterialized(
            config = memoriesConfig(),
            mediaItems = listOf(materialized),
            loadedDayIds = setOf(19870, 19869),
        )

        assertEquals(listOf(materialized), snapshot.items)
        assertEquals(listOf(TimelineDay(dayId = 19870, count = 1)), snapshot.days)
        assertEquals(1, snapshot.slots.size)
        assertEquals(setOf(19870), snapshot.loadedDayIds)
        assertEquals(1, snapshot.totalDayCount)
        assertEquals(1, snapshot.totalMediaCountHint)
    }

    @Test
    fun `materialized snapshot excludes cloud objects without deduplication or position metadata`() {
        val complete = mediaItem(fileId = 42, dayId = 19870).copy(
            auid = "auid-42",
            takenAtEpochSeconds = 1_717_100_000L,
        )
        val withoutAlias = mediaItem(fileId = 43, dayId = 19870).copy(
            takenAtEpochSeconds = 1_717_000_000L,
        )
        val withoutTimestamp = mediaItem(fileId = 44, dayId = 19870).copy(
            buid = "buid-44",
        )

        val snapshot = TimelineSnapshotAssembler.assembleMaterialized(
            config = memoriesConfig(),
            mediaItems = listOf(complete, withoutAlias, withoutTimestamp),
            loadedDayIds = setOf(19870),
        )

        assertEquals(listOf(complete), snapshot.items)
        assertEquals(listOf(TimelineDay(dayId = 19870, count = 1)), snapshot.days)
    }

    @Test
    fun `assemble creates empty snapshot when timeline has no days`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = emptyList(),
        )

        assertEquals(emptyList<TimelineSlot>(), snapshot.slots)
        assertEquals(emptyList<MediaItem>(), snapshot.items)
        assertEquals(emptySet<Int>(), snapshot.loadedDayIds)
        assertEquals(0, snapshot.totalDayCount)
        assertEquals(0, snapshot.totalMediaCountHint)
    }

    @Test
    fun `assemble creates placeholder slots from day counts without loaded items`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(
                TimelineDay(dayId = 19870, count = 2),
                TimelineDay(dayId = 19869, count = 1),
            ),
        )

        assertEquals(3, snapshot.slots.size)
        assertEquals(TimelineSlotKey(dayId = 19870, indexInDay = 0), snapshot.slots[0].key)
        assertEquals(TimelineSlotKey(dayId = 19870, indexInDay = 1), snapshot.slots[1].key)
        assertEquals(TimelineSlotKey(dayId = 19869, indexInDay = 0), snapshot.slots[2].key)
        assertNull(snapshot.slots[0].mediaItem)
        assertEquals(2, snapshot.totalDayCount)
        assertEquals(3, snapshot.totalMediaCountHint)
    }

    @Test
    fun `assemble fills partial loaded day and keeps remaining placeholders`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(dayId = 19870, count = 2)),
            mediaItems = listOf(mediaItem(fileId = 42, dayId = 19870)),
            loadedDayIds = setOf(19870),
        )

        assertEquals(2, snapshot.slots.size)
        assertEquals(42L, snapshot.slots[0].mediaItem?.remoteFileId)
        assertNull(snapshot.slots[1].mediaItem)
        assertEquals(setOf(19870), snapshot.loadedDayIds)
    }

    @Test
    fun `assemble keeps additional slots when server returns more items than day count`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(dayId = 19870, count = 1)),
            mediaItems = listOf(
                mediaItem(fileId = 42, dayId = 19870),
                mediaItem(fileId = 43, dayId = 19870),
            ),
            loadedDayIds = setOf(19870),
        )

        assertEquals(2, snapshot.slots.size)
        assertEquals(listOf(42L, 43L), snapshot.items.map { it.remoteFileId })
        assertEquals(1, snapshot.totalMediaCountHint)
    }

    @Test
    fun `merge loaded items preserves existing days and marks incoming days loaded`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(
                TimelineDay(dayId = 19870, count = 1),
                TimelineDay(dayId = 19869, count = 1),
            ),
            mediaItems = listOf(mediaItem(fileId = 42, dayId = 19870)),
            loadedDayIds = setOf(19870),
        )
        val firstKey = snapshot.slots.first().key

        val updated = TimelineSnapshotAssembler.mergeLoadedItems(
            snapshot = snapshot,
            items = listOf(mediaItem(fileId = 43, dayId = 19869)),
            loadedDayIds = setOf(19869),
        )

        assertEquals(firstKey, updated.slots.first().key)
        assertEquals(listOf(42L, 43L), updated.items.map { it.remoteFileId })
        assertEquals(setOf(19870, 19869), updated.loadedDayIds)
    }

    @Test
    fun `merge loaded items replaces already loaded day details from incoming source`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(dayId = 19870, count = 1)),
            mediaItems = listOf(mediaItem(fileId = 42, dayId = 19870)),
            loadedDayIds = setOf(19870),
        )

        val updated = TimelineSnapshotAssembler.mergeLoadedItems(
            snapshot = snapshot,
            items = listOf(mediaItem(fileId = 43, dayId = 19870)),
            loadedDayIds = setOf(19870),
        )

        assertEquals(listOf(43L), updated.items.map { it.remoteFileId })
        assertEquals(setOf(19870), updated.loadedDayIds)
    }

    @Test
    fun `adding source items keeps remote placeholders and orders all loaded media`() {
        val snapshot = TimelineSnapshotAssembler.assemble(
            config = memoriesConfig(),
            days = listOf(TimelineDay(dayId = 19870, count = 2)),
        )
        val localItem = mediaItem(fileId = 7, dayId = 19871).copy(
            mediaId = MediaId("local-7"),
            takenAtEpochSeconds = 1_717_300_000L,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://media/external/images/media/7",
                modifiedAtEpochSeconds = null,
            ),
        )

        val updated = TimelineSnapshotAssembler.addSourceItems(snapshot, listOf(localItem))

        assertEquals(listOf(19871, 19870), updated.days.map { it.dayId })
        assertEquals(listOf(MediaId("local-7")), updated.items.map { it.mediaId })
        assertEquals(2, updated.slots.count { it.mediaItem == null })
        assertEquals(3, updated.totalMediaCountHint)
    }

    @Test
    fun `local items form a usable snapshot without memories metadata`() {
        val localItem = mediaItem(fileId = 7, dayId = 19871).copy(
            mediaId = MediaId("local-7"),
            remoteFileId = null,
            assetRef = MediaAssetRef.LocalContent(
                contentUri = "content://media/external/images/media/7",
                modifiedAtEpochSeconds = null,
            ),
        )

        val snapshot = TimelineSnapshotAssembler.assembleLocal(listOf(localItem))

        assertEquals(listOf(localItem), snapshot.items)
        assertEquals(setOf(19871), snapshot.loadedDayIds)
        assertEquals("", snapshot.memoriesVersion)
        assertEquals(null, snapshot.timelinePath)
    }

    private fun memoriesConfig(): MemoriesConfig {
        return MemoriesConfig(
            version = "7.5.2",
            timelinePath = "/Photos",
            albumsEnabled = false,
            recognizeEnabled = false,
            faceRecognitionEnabled = false,
            previewGeneratorEnabled = false,
            stackRawFiles = false,
            dedupIdentical = false,
        )
    }

    private fun mediaItem(fileId: Long, dayId: Int): MediaItem {
        return MediaItem(
            mediaId = MediaId("remote-$fileId"),
            remoteFileId = fileId,
            dayId = dayId,
            day = LocalDate.ofEpochDay(dayId.toLong()),
            displayName = "file-$fileId",
            mimeType = "image/jpeg",
            width = null,
            height = null,
            etag = null,
            livePhotoId = null,
            auid = null,
            buid = null,
            sharedBy = null,
            takenAtEpochSeconds = null,
            isVideo = false,
            videoDurationSeconds = null,
            isFavorite = false,
            isHidden = false,
            assetRef = MediaAssetRef.MemoriesFile(photoFileId = fileId),
        )
    }
}
