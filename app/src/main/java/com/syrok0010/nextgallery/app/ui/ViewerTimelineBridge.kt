package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.viewer.ViewerSequence
import com.syrok0010.nextgallery.feature.viewer.reconcileCurrentMedia

internal class ViewerSequenceController {
    private var sourceSnapshot: TimelineSnapshot? = null
    private var liveSequence = ViewerSequence.Empty
    private var displayedSequence = ViewerSequence.Empty

    fun update(
        snapshot: TimelineSnapshot?,
        currentMediaId: MediaId?,
    ): ViewerSequence {
        if (snapshot !== sourceSnapshot) {
            sourceSnapshot = snapshot
            liveSequence = snapshot.toViewerSequence()
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
    snapshot: TimelineSnapshot?,
    currentMediaId: MediaId?,
): ViewerSequence {
    val controller = remember { ViewerSequenceController() }
    return remember(snapshot, currentMediaId) {
        controller.update(snapshot, currentMediaId)
    }
}

internal fun TimelineSnapshot?.toViewerSequence(): ViewerSequence {
    if (this == null) {
        return ViewerSequence.Empty
    }

    val items = ArrayList<MediaItem>(this.items.size)
    val pageIndexByMediaId = LinkedHashMap<MediaId, Int>()

    slots.forEach { slot ->
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
internal class ViewerTimelineIndex(snapshot: TimelineSnapshot?) {
    private val slotsByMediaId = buildMap {
        snapshot?.slots?.forEachIndexed { index, slot ->
            slot.mediaItem?.let { put(it.mediaId, index) }
        }
    }

    fun slotIndex(mediaId: MediaId): Int? = slotsByMediaId[mediaId]

    fun prefetchRange(mediaId: MediaId): IntRange? = slotIndex(mediaId)?.let { slot ->
        (slot - PREFETCH_SLOTS).coerceAtLeast(0)..(slot + PREFETCH_SLOTS)
    }

    private companion object { const val PREFETCH_SLOTS = 240 }
}
