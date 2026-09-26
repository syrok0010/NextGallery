package com.syrok0010.nextgallery.feature.collection

internal sealed interface MediaGridItem {
    val key: String

    data class DayHeader(val dayId: Int) : MediaGridItem {
        override val key: String = "day-header:$dayId"
    }

    data class Slot(val slotIndex: Int, val slot: MediaSlot) : MediaGridItem {
        override val key: String = slot.mediaItem
            ?.let { "media:${it.mediaId.value}" }
            ?: "slot:${slot.key.dayId}:${slot.key.indexInDay}"
    }
}

internal fun List<MediaSlot>.toMediaGridItems(): List<MediaGridItem> {
    val result = mutableListOf<MediaGridItem>()
    var previousDayId: Int? = null

    forEachIndexed { slotIndex, slot ->
        if (slot.dayId != previousDayId) {
            result += MediaGridItem.DayHeader(slot.dayId)
            previousDayId = slot.dayId
        }
        result += MediaGridItem.Slot(slotIndex = slotIndex, slot = slot)
    }

    return result
}

internal fun List<MediaGridItem>.toSlotGridIndexes(): IntArray =
    mapIndexedNotNull { gridIndex, item ->
        if (item is MediaGridItem.Slot) {
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
