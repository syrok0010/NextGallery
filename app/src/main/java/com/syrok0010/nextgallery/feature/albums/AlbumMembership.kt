package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaItem

/** Versioned local overlay over the last server snapshot. */
internal data class PendingAddition(val item: MediaItem?, val version: Long)

internal data class AlbumMembership(
    val snapshot: AlbumContentsBatch = AlbumContentsBatch(emptyList(), 0),
    val initialized: Boolean = false,
    val version: Long = 0,
    val pendingAdded: Map<Long, PendingAddition> = emptyMap(),
) {
    val hasPendingMetadata: Boolean get() = pendingAdded.values.any { it.item == null }

    fun added(ids: Set<Long>, supplied: List<MediaItem>): AlbumMembership {
        if (ids.isEmpty()) return this
        val nextVersion = version + 1
        val known = (snapshot.items + supplied + pendingAdded.values.mapNotNull { it.item })
            .mapNotNull { item -> item.remoteFileId?.let { it to item } }
            .toMap()
        return copy(
            version = nextVersion,
            pendingAdded = pendingAdded +
                ids.associateWith {
                    PendingAddition(known[it] ?: pendingAdded[it]?.item, nextVersion)
                },
        )
    }

    fun resolve(metadata: Map<Long?, MediaItem>): AlbumMembership =
        copy(
            pendingAdded = pendingAdded.mapValues { (id, addition) ->
                addition.copy(item = addition.item ?: metadata[id])
            },
        )

    fun applyRead(
        batch: AlbumContentsBatch,
        startedAtVersion: Long,
        authoritative: Boolean,
        finished: Boolean,
    ): AlbumMembership {
        val batchIds = batch.items.mapNotNullTo(mutableSetOf()) { it.remoteFileId }
        val retained = pendingAdded.filter { (id, addition) ->
            if (!finished || addition.version > startedAtVersion) {
                true
            } else if (id in batchIds) {
                false
            } else {
                !authoritative
            }
        }
        return copy(
            snapshot = batch,
            initialized = if (finished) true else initialized,
            pendingAdded = retained,
        ).resolve(batch.items.associateBy { it.remoteFileId })
    }

    fun contents(): AlbumContentsState {
        val items = (snapshot.items + pendingAdded.values.mapNotNull { it.item })
            .distinctBy { it.mediaId }
        val materialized = items.mapNotNullTo(mutableSetOf()) { it.remoteFileId }
        val pending = pendingAdded.keys.count { it !in materialized }
        val total = if (initialized) {
            items.size + pending
        } else {
            maxOf(
                snapshot.total,
                items.size + pending,
            )
        }
        return AlbumContentsState(items, total, items.size, pendingMetadata = pending)
    }
}
