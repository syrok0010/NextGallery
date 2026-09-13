package com.syrok0010.nextgallery.app.ui

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.TimelineSlot
import com.syrok0010.nextgallery.feature.timeline.TimelineSlotKey

/** Input is already sorted by canonical day and time by the collection's projection. */
internal fun List<MediaItem>.toMediaSlots(): List<TimelineSlot> {
    val counts = mutableMapOf<Int, Int>()
    return map { item ->
        val index = counts.getOrDefault(item.dayId, 0)
        counts[item.dayId] = index + 1
        TimelineSlot(TimelineSlotKey(item.dayId, index), item.dayId, index, item)
    }
}
