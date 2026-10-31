package com.syrok0010.nextgallery.feature.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import com.syrok0010.nextgallery.feature.viewer.reconcileCurrentMedia

internal data class ViewerSequenceState(
    val sequence: ViewerSequence,
    val replacementMediaId: MediaId? = null,
    val loadingRange: IntRange? = null,
)

internal class ViewerSequenceController {
    private var sourceSlots: List<MediaSlot>? = null
    private var liveSequence = ViewerSequence.Empty
    private var displayedSequence = ViewerSequence.Empty
    private var anchor: MediaSlot? = null
    var state = ViewerSequenceState(ViewerSequence.Empty)
        private set

    fun updateSlots(
        slots: List<MediaSlot>,
        currentMediaId: MediaId?,
        completedDeletions: Set<MediaId> = emptySet(),
    ): ViewerSequence {
        // Keep the position even if the local observer removes the object before completion.
        if (anchor?.mediaItem?.mediaId != currentMediaId) anchor = null
        (
            slots.firstOrNull { it.mediaItem?.mediaId == currentMediaId }
                ?: sourceSlots?.firstOrNull { it.mediaItem?.mediaId == currentMediaId }
            )?.let { anchor = it }
        if (slots !== sourceSlots) {
            sourceSlots = slots
            liveSequence = slots.toViewerSequence()
        }

        var replacement: MediaId? = null
        var loadingRange: IntRange? = null
        val removed = currentMediaId != null && currentMediaId in completedDeletions && currentMediaId !in liveSequence
        if (removed && slots.isNotEmpty()) {
            val position = anchor
            val next = if (position == null) {
                0
            } else {
                slots.indexOfFirst {
                    it.dayId < position.dayId ||
                        (it.dayId == position.dayId && it.indexInDay >= position.indexInDay)
                }
            }
            val target = if (next < 0) slots.lastIndex else next
            replacement = slots[target].mediaItem?.mediaId
            if (replacement == null) {
                loadingRange = (target - 240).coerceAtLeast(
                    0,
                )..(target + 240).coerceAtMost(slots.lastIndex)
            }
        }
        displayedSequence = reconcileCurrentMedia(
            live = liveSequence,
            previous = displayedSequence,
            currentMediaId = currentMediaId?.takeUnless { removed && loadingRange == null },
        )
        state = ViewerSequenceState(displayedSequence, replacement, loadingRange)
        return displayedSequence
    }
}

@Composable
internal fun rememberViewerSequenceState(
    slots: List<MediaSlot>,
    currentMediaId: MediaId?,
    completedDeletions: Set<MediaId> = emptySet(),
): ViewerSequenceState {
    val controller = remember { ViewerSequenceController() }
    return remember(slots, currentMediaId, completedDeletions) {
        controller.updateSlots(slots, currentMediaId, completedDeletions)
        controller.state
    }
}

@Composable
internal fun rememberViewerSequence(
    slots: List<MediaSlot>,
    currentMediaId: MediaId?,
    completedDeletions: Set<MediaId> = emptySet(),
): ViewerSequence = rememberViewerSequenceState(slots, currentMediaId, completedDeletions).sequence

internal fun List<MediaSlot>.toViewerSequence(): ViewerSequence {
    if (isEmpty()) return ViewerSequence.Empty
    val items = ArrayList<MediaItem>(size)
    val pageIndexByMediaId = LinkedHashMap<MediaId, Int>()

    forEach { slot ->
        val item = slot.mediaItem ?: return@forEach
        pageIndexByMediaId[item.mediaId] = items.size
        items += item
    }

    return ViewerSequence(
        items = items,
        pageIndexByMediaId = pageIndexByMediaId,
    )
}

/** App-owned mapping: viewer never needs timeline slots or hydration policy. */
internal class ViewerMediaIndex(slots: List<MediaSlot>) {
    private val slotsByMediaId = buildMap {
        slots.forEachIndexed { index, slot ->
            slot.mediaItem?.let { put(it.mediaId, index) }
        }
    }

    fun slotIndex(mediaId: MediaId): Int? = slotsByMediaId[mediaId]

    fun prefetchRange(mediaId: MediaId): IntRange? =
        slotIndex(mediaId)?.let { slot ->
            (slot - PREFETCH_SLOTS).coerceAtLeast(0)..(slot + PREFETCH_SLOTS)
        }

    private companion object {
        const val PREFETCH_SLOTS = 240
    }
}
