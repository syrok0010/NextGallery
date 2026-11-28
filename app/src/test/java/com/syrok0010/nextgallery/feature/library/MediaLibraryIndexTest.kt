package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.network.memories.MemoriesPhotoDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaLibraryIndexTest {
    private val remote = MemoriesPhotoDto(
        fileid = 42,
        dayid = 20,
        epoch = 1_728_000,
    ).toMemoriesMediaItem(MediaId("shared"))

    private val local = remote.copy(
        dayId = 19,
        takenAtEpochSeconds = 1_641_600,
        assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/7", 100, 1),
    )

    @Test fun `observed remote copy merges with known local copy and preserves cloud metadata`() {
        val index = MediaLibraryIndex()
        index.publish(listOf(local))
        index.observeRemote(listOf(remote))

        val merged = requireNotNull(index[remote.mediaId])
        assertEquals(remote.dayId, merged.dayId)
        assertEquals(remote.takenAtEpochSeconds, merged.takenAtEpochSeconds)
        assertEquals(
            MediaAssetRef.LocalFirst(
                local.assetRef as MediaAssetRef.LocalContent,
                remote.assetRef as MediaAssetRef.MemoriesFile,
            ),
            merged.assetRef,
        )
    }

    @Test fun `observed remote copy survives timeline publishes and drops local copy when access ends`() {
        val index = MediaLibraryIndex()
        val timelineOnly = remote.copy(mediaId = MediaId("timeline"), assetRef = MediaAssetRef.MemoriesFile(99))
        index.observeRemote(listOf(remote))
        index.publish(listOf(local, timelineOnly))

        assertTrue(index[remote.mediaId]?.assetRef is MediaAssetRef.LocalFirst)
        assertEquals(timelineOnly, index[timelineOnly.mediaId])

        index.publish(listOf(timelineOnly))
        assertEquals(remote.assetRef, index[remote.mediaId]?.assetRef)
    }

    @Test fun `forgetRemote removes cloud-only objects and downgrades merged objects to local`() {
        val index = MediaLibraryIndex()
        val cloudOnly = remote.copy(mediaId = MediaId("cloud-only"), assetRef = MediaAssetRef.MemoriesFile(77))
        index.publish(listOf(local))
        index.observeRemote(listOf(remote, cloudOnly))

        index.forgetRemote(setOf(42L, 77L))

        assertNull(index[cloudOnly.mediaId])
        assertEquals(local.assetRef, index[remote.mediaId]?.assetRef)
        assertNull(index.findByRemoteFileId(42L))
    }
}
