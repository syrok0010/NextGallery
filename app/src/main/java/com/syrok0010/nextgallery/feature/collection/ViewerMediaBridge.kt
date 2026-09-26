package com.syrok0010.nextgallery.feature.collection

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import com.syrok0010.nextgallery.feature.viewer.reconcileCurrentMedia

internal class ViewerSequenceController {
    private var sourceSlots: List<MediaSlot>? = null
    private var liveSequence = ViewerSequence.Empty
    private var displayedSequence = ViewerSequence.Empty

    fun updateSlots(slots: List<MediaSlot>, currentMediaId: MediaId?): ViewerSequence {
        if (slots !== sourceSlots) {
            sourceSlots = slots
            liveSequence = slots.toViewerSequence()
        }

        displayedSequence = reconcileCurrentMedia(
            live = liveSequence,
            previous = displayedSequence,
            currentMediaId = currentMediaId,
        )
        return displayedSequence
    }
}

@Composable
internal fun rememberViewerSequence(
    slots: List<MediaSlot>,
    currentMediaId: MediaId?,
): ViewerSequence {
    val controller = remember { ViewerSequenceController() }
    return remember(slots, currentMediaId) {
        controller.updateSlots(slots, currentMediaId)
    }
}

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
