package com.syrok0010.nextgallery.app.ui

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.TimelineSlot
import com.syrok0010.nextgallery.feature.timeline.TimelineSlotKey

/** Adapts a collection to the grid's stable day and time ordering. */
internal fun List<MediaItem>.toMediaSlots(): List<TimelineSlot> {
    val counts = mutableMapOf<Int, Int>()
    return sortedWith(
        compareByDescending<MediaItem> { it.dayId }
            .thenByDescending { it.takenAtEpochSeconds }
            .thenBy { it.mediaId.value },
    ).map { item ->
        val index = counts.getOrDefault(item.dayId, 0)
        counts[item.dayId] = index + 1
        TimelineSlot(TimelineSlotKey(item.dayId, index), item.dayId, index, item)
    }
}
