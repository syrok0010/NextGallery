package com.syrok0010.nextgallery.app.ui

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.feature.albums.albumMediaProjection
import com.syrok0010.nextgallery.feature.timeline.remote.MemoriesPhotoDto
import com.syrok0010.nextgallery.feature.timeline.remote.toMediaItem
import org.junit.Assert.*
import org.junit.Test

class AlbumMediaProjectionTest {
    private val remote = MemoriesPhotoDto(
        42,
        20,
        epoch = 1_728_000,
    ).toMediaItem(MediaId("shared-object"))
    private val local = remote.copy(
        dayId = 19,
        takenAtEpochSeconds = 1_641_600,
        assetRef = MediaAssetRef.LocalContent("content://media/external/images/media/7", 100),
    )

    @Test fun `folder keeps canonical cloud date and both copies without importing other media`() {
        val unrelated = remote.copy(mediaId = MediaId("unrelated"))
        val result = albumMediaProjection(
            listOf(local),
            listOf(remote, unrelated).associateBy {
                it.mediaId
            },
            true,
        ).single()
        assertEquals(remote.dayId, result.dayId)
        assertEquals(remote.takenAtEpochSeconds, result.takenAtEpochSeconds)
        assertEquals(
            MediaAssetRef.LocalFirst(
                local.assetRef as MediaAssetRef.LocalContent,
                remote.assetRef as MediaAssetRef.MemoriesFile,
            ),
            result.assetRef,
        )
        assertEquals(remote.mediaId, result.mediaId)
    }

    @Test fun `server album prefers known local copy only while permission allows it`() {
        val library = mapOf(local.mediaId to local)
        assertTrue(
            albumMediaProjection(
                listOf(remote),
                library,
                true,
            ).single().assetRef is MediaAssetRef.LocalFirst,
        )
        assertEquals(
            remote.assetRef,
            albumMediaProjection(listOf(remote), library, false).single().assetRef,
        )
    }
}
