package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.memories.MediaAssetRef
import com.syrok0010.nextgallery.domain.media.MediaId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMediaSourceTest {
    @Test
    fun `maps image and video rows and uses taken modified added date priority`() = runBlocking {
        val rows = listOf(
            row("content://images/1", taken = 300_000, modified = 200, added = 100),
            row("content://video/2", taken = null, modified = 250, added = 150, video = true),
            row("content://images/3", taken = 0, modified = 0, added = 175),
        )
        val source = LocalMediaSource(
            gateway = LocalMediaGateway { rows },
            resolveMediaIds = { uris -> uris.associateWith { MediaId("stable:$it") } },
        )

        val items = source.readAll()

        assertEquals(listOf(300L, 250L, 175L), items.map { it.takenAtEpochSeconds })
        assertEquals(listOf(false, true, false), items.map { it.isVideo })
        assertEquals(2L, items[1].videoDurationSeconds)
        assertEquals(
            MediaAssetRef.LocalContent("content://video/2"),
            items[1].assetRef,
        )
        assertTrue(items.all { it.mediaId.value.startsWith("stable:content://") })
    }

    @Test
    fun `drops rows without any usable timeline date`() = runBlocking {
        val source = LocalMediaSource(
            gateway = LocalMediaGateway { listOf(row("content://images/1")) },
            resolveMediaIds = { uris -> uris.associateWith { MediaId("stable:$it") } },
        )

        assertEquals(emptyList<Any>(), source.readAll())
    }

    private fun row(
        uri: String,
        taken: Long? = null,
        modified: Long? = null,
        added: Long? = null,
        video: Boolean = false,
    ) = LocalMediaRow(
        contentUri = uri,
        displayName = uri.substringAfterLast('/'),
        mimeType = if (video) "video/mp4" else "image/jpeg",
        width = 100,
        height = 200,
        sizeBytes = 1_000,
        dateTakenMillis = taken,
        dateModifiedSeconds = modified,
        dateAddedSeconds = added,
        durationMillis = if (video) 2_500 else null,
        isVideo = video,
    )
}
