package com.syrok0010.nextgallery.data.local

import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineDay
import com.syrok0010.nextgallery.data.memories.TimelineSlot
import com.syrok0010.nextgallery.data.memories.TimelineSlotKey
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot

object LocalTimelineProjector {
    fun project(remote: TimelineSnapshot, localItems: List<MediaItem>): TimelineSnapshot {
        if (localItems.isEmpty()) {
            return remote
        }

        val remoteSlotsByDay = remote.slots.groupBy { it.dayId }
        val localItemsByDay = localItems.groupBy { it.dayId }
        val dayIds = (remote.days.map { it.dayId } + localItems.map { it.dayId })
            .distinct()
            .sortedDescending()
        val days = dayIds.map { dayId ->
            TimelineDay(
                dayId = dayId,
                count = remote.days.firstOrNull { it.dayId == dayId }?.count.orZero() +
                    localItemsByDay[dayId].orEmpty().size,
            )
        }
        val slots = dayIds.flatMap { dayId ->
            val remoteSlots = remoteSlotsByDay[dayId].orEmpty()
            val actualItems = (remoteSlots.mapNotNull { it.mediaItem } + localItemsByDay[dayId].orEmpty())
                .sortedWith(compareByDescending<MediaItem> { it.takenAtEpochSeconds }.thenByDescending { it.mediaId.value })
            val placeholderCount = remoteSlots.count { it.mediaItem == null }
            List(actualItems.size + placeholderCount) { index ->
                TimelineSlot(
                    key = TimelineSlotKey(dayId = dayId, indexInDay = index),
                    dayId = dayId,
                    indexInDay = index,
                    mediaItem = actualItems.getOrNull(index),
                )
            }
        }

        return remote.copy(
            days = days,
            slots = slots,
            totalDayCount = days.size,
            totalMediaCountHint = remote.totalMediaCountHint + localItems.size,
        )
    }

    private fun Int?.orZero(): Int = this ?: 0
}
