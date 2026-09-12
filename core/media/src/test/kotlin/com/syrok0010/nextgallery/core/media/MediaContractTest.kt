package com.syrok0010.nextgallery.core.media

import org.junit.Assert.*
import org.junit.Test

class MediaContractTest {
    @Test fun `local identity takes precedence when matching a previously remote alias`() {
        val remote = MediaSourceIdentity(MediaSourceKind.Memories, "42")
        val local = MediaSourceIdentity(MediaSourceKind.Local, "content://media/1")
        val alias = MediaAlias(MediaAliasKind.Auid, "same")
        val old = MediaId("remote")
        val result = reconcileMediaIdentities(
            candidates = listOf(MediaIdentityCandidate(local, MediaId("local"), setOf(alias))),
            initialSourceMediaIds = mapOf(remote to old), initialAliasMediaIds = mapOf(alias to old),
            initialLocalMediaIds = emptySet(), mediaIdFactory = { error("published ID is present") },
        )
        assertEquals(MediaId("local"), result.resolution.mediaIds[local])
        assertEquals(MediaId("local"), result.sourceMediaIds[remote])
        assertEquals(listOf(old to MediaId("local")), result.reassignments)
    }

    @Test fun `derived identity and day stay consistent through copy`() {
        val item = item(MediaAssetRef.MemoriesFile(42))
        val updated = item.copy(dayId = 20_001, assetRef = MediaAssetRef.MemoriesFile(43))
        assertEquals(20_001L, updated.day.toEpochDay())
        assertEquals(43L, updated.remoteFileId)
    }

    @Test fun `unified output is rejected by source projection boundaries`() {
        val item = item(MediaAssetRef.LocalFirst(MediaAssetRef.LocalContent("content://media/1", 1), MediaAssetRef.MemoriesFile(42)))
        assertThrows(IllegalArgumentException::class.java) { LocalMediaProjection(listOf(item)) }
        assertThrows(IllegalArgumentException::class.java) { RemoteMediaProjection(listOf(item)) }
    }

    @Test fun `source projection owns its validated list`() {
        val items = mutableListOf(item(MediaAssetRef.MemoriesFile(42)))
        val projection = RemoteMediaProjection(items)
        items.clear()
        assertEquals(1, projection.items.size)
        assertThrows(UnsupportedOperationException::class.java) {
            (projection.items as MutableList).clear()
        }
    }

    private fun item(asset: MediaAssetRef) = MediaItem(
        MediaId("one"), 20_000, "one.jpg", "image/jpeg", 10, 10,
        null, null, null, null, null, 1, false, null, false, false, asset,
    )
}
