package com.syrok0010.nextgallery.feature.collection

import com.syrok0010.nextgallery.core.media.MediaItem

/** Adapts a collection to the grid's stable day and time ordering. */
internal fun List<MediaItem>.toMediaSlots(): List<MediaSlot> {
    val counts = mutableMapOf<Int, Int>()
    return sortedWith(
        compareByDescending<MediaItem> { it.dayId }
            .thenByDescending { it.takenAtEpochSeconds }
            .thenBy { it.mediaId.value },
    ).map { item ->
        val index = counts.getOrDefault(item.dayId, 0)
        counts[item.dayId] = index + 1
        MediaSlot(MediaSlotKey(item.dayId, index), item.dayId, index, item)
    }
}
