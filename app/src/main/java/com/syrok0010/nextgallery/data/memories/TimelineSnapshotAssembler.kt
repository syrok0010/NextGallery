package com.syrok0010.nextgallery.data.memories

object TimelineSnapshotAssembler {
    fun assemble(
        config: MemoriesConfig?,
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

    fun assembleLocal(items: List<MediaItem>): TimelineSnapshot {
        val empty = assemble(
            config = null,
            days = emptyList(),
            loadedDayIds = items.mapTo(mutableSetOf()) { it.dayId },
        )
        return addSourceItems(empty, items)
    }

    fun assembleMaterialized(
        config: MemoriesConfig,
        mediaItems: List<MediaItem>,
        loadedDayIds: Set<Int>,
    ): TimelineSnapshot {
        val materializedItems = mediaItems.filter { it.hasMaterializedRemoteMetadata() }
        val itemsByDay = materializedItems.groupBy { it.dayId }
        val days = itemsByDay
            .map { (dayId, items) -> TimelineDay(dayId = dayId, count = items.size) }
            .sortedByDescending(TimelineDay::dayId)
        val materializedDayIds = days.mapTo(mutableSetOf(), TimelineDay::dayId)

        return assemble(
            config = config,
            days = days,
            mediaItems = materializedItems,
            loadedDayIds = loadedDayIds.intersect(materializedDayIds),
        )
    }

    private fun MediaItem.hasMaterializedRemoteMetadata(): Boolean =
        remoteFileId != null &&
            takenAtEpochSeconds != null &&
            (!auid.isNullOrBlank() || !buid.isNullOrBlank())

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

    fun addSourceItems(
        snapshot: TimelineSnapshot,
        items: List<MediaItem>,
    ): TimelineSnapshot {
        if (items.isEmpty()) return snapshot

        val currentSlotsByDay = snapshot.slots.groupBy { it.dayId }
        val additionalItemsByDay = items.groupBy { it.dayId }
        val dayIds = (snapshot.days.map { it.dayId } + items.map { it.dayId })
            .distinct()
            .sortedDescending()
        val days = dayIds.map { dayId ->
            TimelineDay(
                dayId = dayId,
                count = snapshot.days.firstOrNull { it.dayId == dayId }?.count.orZero() +
                    additionalItemsByDay[dayId].orEmpty().size,
            )
        }
        val mergedItemsByDay = dayIds.associateWith { dayId ->
            (currentSlotsByDay[dayId].orEmpty().mapNotNull { it.mediaItem } +
                additionalItemsByDay[dayId].orEmpty())
                .sortedWith(
                    compareByDescending<MediaItem> { it.takenAtEpochSeconds }
                        .thenByDescending { it.mediaId.value },
                )
        }
        val placeholderCountsByDay = currentSlotsByDay.mapValues { (_, slots) ->
            slots.count { it.mediaItem == null }
        }

        return snapshot.copy(
            days = days,
            slots = buildTimelineSlots(days, mergedItemsByDay, placeholderCountsByDay),
            totalDayCount = days.size,
            totalMediaCountHint = snapshot.totalMediaCountHint + items.size,
        )
    }

    private fun buildTimelineSlots(
        days: List<TimelineDay>,
        itemsByDay: Map<Int, List<MediaItem>>,
        placeholderCountsByDay: Map<Int, Int>? = null,
    ): List<TimelineSlot> {
        return days.flatMap { day ->
            val items = itemsByDay[day.dayId].orEmpty()
            val slotCount = placeholderCountsByDay?.let { items.size + it.getOrDefault(day.dayId, 0) }
                ?: maxOf(day.count, items.size)

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

    private fun Int?.orZero(): Int = this ?: 0
}
