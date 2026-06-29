package com.syrok0010.nextgallery.data.memories

object TimelineSnapshotAssembler {
    fun assemble(
        config: MemoriesConfig,
        days: List<TimelineDay>,
        mediaItems: List<MediaItem> = emptyList(),
        loadedDayIds: Set<Int> = emptySet(),
    ): TimelineSnapshot {
        val itemsByDay = mediaItems.groupBy { it.dayId }

        return TimelineSnapshot(
            config = config,
            days = days,
            slots = buildTimelineSlots(days, itemsByDay),
            loadedDayIds = loadedDayIds,
            totalDayCount = days.size,
            totalMediaCountHint = days.sumOf { it.count },
        )
    }

    fun mergeLoadedItems(
        snapshot: TimelineSnapshot,
        items: List<MediaItem>,
        loadedDayIds: Set<Int>,
    ): TimelineSnapshot {
        val currentItemsByDay = snapshot.slots
            .mapNotNull { it.mediaItem }
            .groupBy { it.dayId }
        val incomingItemsByDay = items.groupBy { it.dayId }
        val mergedItemsByDay = currentItemsByDay + incomingItemsByDay

        return snapshot.copy(
            slots = buildTimelineSlots(snapshot.days, mergedItemsByDay),
            loadedDayIds = snapshot.loadedDayIds + loadedDayIds,
        )
    }

    private fun buildTimelineSlots(
        days: List<TimelineDay>,
        itemsByDay: Map<Int, List<MediaItem>>,
    ): List<TimelineSlot> {
        return days.flatMap { day ->
            val items = itemsByDay[day.dayId].orEmpty()
            val slotCount = maxOf(day.count, items.size)

            List(slotCount) { index ->
                TimelineSlot(
                    key = TimelineSlotKey(dayId = day.dayId, indexInDay = index),
                    dayId = day.dayId,
                    indexInDay = index,
                    mediaItem = items.getOrNull(index),
                )
            }
        }
    }
}
