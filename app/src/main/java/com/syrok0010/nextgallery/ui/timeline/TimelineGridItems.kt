package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.data.memories.TimelineSlot

internal sealed interface TimelineGridItem {
    val key: String

    data class DayHeader(val dayId: Int) : TimelineGridItem {
        override val key: String = "day-header:$dayId"
    }

    data class Slot(
        val slotIndex: Int,
        val slot: TimelineSlot,
    ) : TimelineGridItem {
        override val key: String = "slot:${slot.key.dayId}:${slot.key.indexInDay}"
    }
}

internal fun List<TimelineSlot>.toTimelineGridItems(): List<TimelineGridItem> {
    val result = mutableListOf<TimelineGridItem>()
    var previousDayId: Int? = null

    forEachIndexed { slotIndex, slot ->
        if (slot.dayId != previousDayId) {
            result += TimelineGridItem.DayHeader(slot.dayId)
            previousDayId = slot.dayId
        }
        result += TimelineGridItem.Slot(slotIndex = slotIndex, slot = slot)
    }

    return result
}

internal fun List<TimelineGridItem>.toSlotGridIndexes(): IntArray =
    mapIndexedNotNull { gridIndex, item ->
        if (item is TimelineGridItem.Slot) {
            gridIndex
        } else {
            null
        }
    }.toIntArray()

internal fun IntArray.gridIndexAtFraction(fraction: Float): Int? {
    if (isEmpty()) {
        return null
    }

    val slotIndex = ((size - 1) * fraction.coerceIn(0f, 1f)).toInt()
    return this[slotIndex]
}
