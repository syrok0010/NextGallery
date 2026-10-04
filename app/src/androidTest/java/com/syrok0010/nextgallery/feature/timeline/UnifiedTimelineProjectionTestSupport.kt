package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.app.library.TimelineSnapshotProjection
import com.syrok0010.nextgallery.core.media.LocalMediaProjection
import com.syrok0010.nextgallery.core.media.MediaIdentityConflict
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.RemoteMediaProjection
import com.syrok0010.nextgallery.feature.library.projectMediaLibrary
import com.syrok0010.nextgallery.feature.library.sourceIdentity

/** Test composition for the two production projections; no combined production module is needed. */
internal class UnifiedTimelineProjection {
    private var remote: TimelineSnapshot? = null
    private var local = emptyList<MediaItem>()
    private var projected: TimelineSnapshot? = null

    val snapshot get() = projected

    suspend fun replaceLocalItems(items: LocalMediaProjection): Result {
        local = items.items
        return project()
    }

    suspend fun replaceRemoteSnapshot(snapshot: TimelineSnapshot?): Result {
        remote = snapshot
        return project()
    }

    suspend fun mergeRemoteItems(items: RemoteMediaProjection, loadedDayIds: Set<Int>): Result {
        remote = remote?.let {
            TimelineSnapshotAssembler.mergeLoadedItems(
                it,
                items.items,
                loadedDayIds,
            )
        }
        return project()
    }

    suspend fun clear() {
        remote = null
        local = emptyList()
        projected = null
    }

    private suspend fun project(): Result {
        val canonical = projectMediaLibrary(local, remote?.items.orEmpty())
        remote = remote?.copy(
            slots = remote!!.slots.map { slot ->
                val item = slot.mediaItem ?: return@map slot
                slot.copy(
                    mediaItem = item.copy(
                        mediaId = checkNotNull(canonical.mediaIdsBySource[item.sourceIdentity()]),
                    ),
                )
            },
        )
        projected = TimelineSnapshotProjection.project(remote, canonical)
        return Result(projected)
    }

    data class Result(val snapshot: TimelineSnapshot?)
}
