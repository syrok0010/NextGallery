package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.TimelineSlot
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import com.syrok0010.nextgallery.feature.viewer.reconcileCurrentMedia

internal class ViewerSequenceController {
    private var sourceSlots: List<TimelineSlot>? = null
    private var liveSequence = ViewerSequence.Empty
    private var displayedSequence = ViewerSequence.Empty

    fun updateSlots(slots: List<TimelineSlot>, currentMediaId: MediaId?): ViewerSequence {
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

    internal fun update(snapshot: TimelineSnapshot?, currentMediaId: MediaId?): ViewerSequence =
        updateSlots(snapshot?.slots.orEmpty(), currentMediaId)
}

@Composable
internal fun rememberViewerSequence(
    slots: List<TimelineSlot>,
    currentMediaId: MediaId?,
): ViewerSequence {
    val controller = remember { ViewerSequenceController() }
    return remember(slots, currentMediaId) {
        controller.updateSlots(slots, currentMediaId)
    }
}

internal fun List<TimelineSlot>.toViewerSequence(): ViewerSequence {
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

internal fun TimelineSnapshot?.toViewerSequence(): ViewerSequence =
    this?.slots.orEmpty().toViewerSequence()

/** App-owned mapping: viewer never needs timeline slots or hydration policy. */
internal class ViewerTimelineIndex(slots: List<TimelineSlot>) {
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

    internal constructor(snapshot: TimelineSnapshot?) : this(snapshot?.slots.orEmpty())

    private companion object {
        const val PREFETCH_SLOTS = 240
    }
}
