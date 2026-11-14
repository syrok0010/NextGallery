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

/**
 * One list of [slots] as the grid shows it. For a frame after an update the measured layout can
 * still show the earlier list: its indexes then point at other objects, its keys never do, so
 * measured items are resolved here by key.
 */
internal class MediaGridSnapshot(val slots: List<MediaSlot>) {
    val items: List<MediaGridItem> = slots.toMediaGridItems()

    /** Grid index of every slot, for jumps to a position in the list. */
    val slotGridIndexes: IntArray = items.toSlotGridIndexes()
    private val byKey = items.associateBy(MediaGridItem::key)

    /** Null for an item the current list no longer holds. */
    operator fun get(key: Any): MediaGridItem? = byKey[key]

    /** The slots among the measured [keys], in their order; items gone from the list are left out. */
    fun measuredSlots(keys: List<Any>): List<MediaGridItem.Slot> =
        keys.mapNotNull { byKey[it] as? MediaGridItem.Slot }
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
