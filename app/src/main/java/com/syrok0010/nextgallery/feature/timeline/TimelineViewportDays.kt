package com.syrok0010.nextgallery.feature.timeline

/** Translate displayed coordinates before crossing the remote loading seam. */
internal fun TimelineSnapshot.daysForSlots(range: IntRange): List<Int> {
    val start = (range.first - 12).coerceAtLeast(0)
    val end = (range.last + 12).coerceAtMost(slots.lastIndex)
    if (start > end) return emptyList()
    return slots.subList(start, end + 1).map { it.dayId }.distinct()
}
