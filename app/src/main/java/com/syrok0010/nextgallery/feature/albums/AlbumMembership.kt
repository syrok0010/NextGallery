package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaItem

/** Last accepted membership plus confirmations not yet acknowledged by a complete read. */
internal data class AlbumMembership(
    val snapshot: AlbumContentsBatch = AlbumContentsBatch(emptyList(), 0),
    val initialized: Boolean = false,
    private val confirmed: Map<Long, MediaItem?> = emptyMap(),
) {
    fun added(ids: Set<Long>, supplied: List<MediaItem>): AlbumMembership {
        val known = (snapshot.items + supplied).associateBy { it.remoteFileId }
        return copy(confirmed = confirmed + ids.associateWith { known[it] ?: confirmed[it] })
    }

    fun removed(ids: Set<Long>): AlbumMembership = copy(
        snapshot = snapshot.copy(items = snapshot.items.filterNot { it.remoteFileId in ids }),
        confirmed = confirmed - ids,
    )

    fun resolve(metadata: Map<Long?, MediaItem>): AlbumMembership =
        copy(confirmed = confirmed.mapValues { (id, item) -> item ?: metadata[id] })

    fun read(batch: AlbumContentsBatch, finished: Boolean): AlbumMembership {
        val metadata = batch.items.associateBy { it.remoteFileId }
        val retained = if (finished) confirmed.filterKeys { it !in metadata } else confirmed
        return copy(
            snapshot = batch,
            initialized = finished,
            confirmed = retained,
        ).resolve(metadata)
    }

    /** A refresh retires confirmations, retaining visible cards until a complete replacement arrives. */
    fun authoritative(): AlbumMembership {
        val visible = contents()
        return copy(
            snapshot = AlbumContentsBatch(visible.items, visible.total),
            confirmed = emptyMap(),
        )
    }

    fun contents(): AlbumContentsState {
        val items = (snapshot.items + confirmed.values.filterNotNull()).distinctBy { it.mediaId }
        val materialized = items.mapNotNullTo(mutableSetOf()) { it.remoteFileId }
        val pending = confirmed.keys.count { it !in materialized }
        return AlbumContentsState(
            items = items,
            loaded = items.size,
            total = maxOf(if (initialized) 0 else snapshot.total, items.size + pending),
            pendingMetadata = pending,
        )
    }
}
