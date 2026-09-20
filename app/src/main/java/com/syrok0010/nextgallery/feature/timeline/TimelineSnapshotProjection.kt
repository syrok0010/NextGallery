package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.feature.library.CanonicalMediaLibrary
import com.syrok0010.nextgallery.feature.library.sourceIdentity

/** Projects the canonical media library onto the ordering and loading state of a remote timeline. */
internal object TimelineSnapshotProjection {
    fun project(remote: TimelineSnapshot?, library: CanonicalMediaLibrary): TimelineSnapshot? {
        if (remote == null) {
            return library.items.takeIf { it.isNotEmpty() }?.let(
                TimelineSnapshotAssembler::assembleLocal,
            )
        }
        val itemsById = library.items.associateBy { it.mediaId }
        val remoteMediaIds = remote.items.mapNotNullTo(mutableSetOf()) { source ->
            library.mediaIdsBySource[source.sourceIdentity()]
        }
        val resolvedRemote = remote.copy(
            slots = remote.slots.map { slot ->
                val source = slot.mediaItem ?: return@map slot
                val mediaId = checkNotNull(library.mediaIdsBySource[source.sourceIdentity()])
                slot.copy(mediaItem = checkNotNull(itemsById[mediaId]))
            },
        )
        val localOnly = library.items.filterNot { it.mediaId in remoteMediaIds }
        val remoteDayIds = remote.days.mapTo(mutableSetOf()) { it.dayId }
        val localOnlyDayIds = localOnly.mapNotNullTo(mutableSetOf()) { item ->
            item.dayId.takeUnless { it in remoteDayIds }
        }
        return TimelineSnapshotAssembler.addSourceItems(resolvedRemote, localOnly).copy(
            loadedDayIds = resolvedRemote.loadedDayIds + localOnlyDayIds,
        )
    }
}
