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
            initialSourceMediaIds = mapOf(remote to old),
            initialAliasMediaIds = mapOf(alias to old),
            initialLocalMediaIds = emptySet(),
            mediaIdFactory = { error("published ID is present") },
        )
        assertEquals(MediaId("local"), result.resolution.mediaIds[local])
        assertEquals(MediaId("local"), result.sourceMediaIds[remote])
        assertEquals(listOf(old to MediaId("local")), result.reassignments)
    }

    @Test fun `two server files with one alias stay separate objects`() {
        val first = MediaSourceIdentity(MediaSourceKind.Memories, "42")
        val second = MediaSourceIdentity(MediaSourceKind.Memories, "43")
        val alias = MediaAlias(MediaAliasKind.Auid, "same")
        val result = reconcileMediaIdentities(
            candidates = listOf(
                MediaIdentityCandidate(first, MediaId("first"), setOf(alias)),
                MediaIdentityCandidate(second, MediaId("second"), setOf(alias)),
            ),
            initialSourceMediaIds = emptyMap(),
            initialAliasMediaIds = emptyMap(),
            initialLocalMediaIds = emptySet(),
            mediaIdFactory = { error("published ID is present") },
        )
        assertEquals(MediaId("first"), result.resolution.mediaIds[first])
        assertEquals(MediaId("second"), result.resolution.mediaIds[second])
        assertEquals(listOf(second), result.resolution.conflicts.map { it.source })
        assertEquals(MediaId("first"), result.aliasMediaIds[alias])
    }

    @Test fun `alias does not join an object whose server copy is known only by id`() {
        val local = MediaSourceIdentity(MediaSourceKind.Local, "content://media/1")
        val linked = MediaSourceIdentity(MediaSourceKind.Memories, "42")
        val other = MediaSourceIdentity(MediaSourceKind.Memories, "43")
        val alias = MediaAlias(MediaAliasKind.Auid, "same")
        val shared = MediaId("shared")
        val result = reconcileMediaIdentities(
            candidates = listOf(MediaIdentityCandidate(other, MediaId("other"), setOf(alias))),
            initialSourceMediaIds = emptyMap(),
            initialAliasMediaIds = mapOf(alias to shared),
            initialLocalMediaIds = setOf(shared),
            mediaIdFactory = { error("published ID is present") },
            initialMediaSources = mapOf(shared to setOf(local, linked)),
        )
        assertEquals(MediaId("other"), result.resolution.mediaIds[other])
        assertEquals(shared, result.aliasMediaIds[alias])
    }

    @Test fun `derived identity and day stay consistent through copy`() {
        val item = item(MediaAssetRef.MemoriesFile(42))
        val updated = item.copy(dayId = 20_001, assetRef = MediaAssetRef.MemoriesFile(43))
        assertEquals(20_001L, updated.day.toEpochDay())
        assertEquals(43L, updated.remoteFileId)
    }

    @Test fun `unified output is rejected by source projection boundaries`() {
        val item =
            item(
                MediaAssetRef.LocalFirst(
                    MediaAssetRef.LocalContent("content://media/1", 1),
                    MediaAssetRef.MemoriesFile(42),
                ),
            )
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

    private fun item(asset: MediaAssetRef) =
        MediaItem(
            MediaId("one"), 20_000, "one.jpg", "image/jpeg", 10, 10,
            null, null, null, null, null, 1, false, null, false, false, asset,
        )
}
