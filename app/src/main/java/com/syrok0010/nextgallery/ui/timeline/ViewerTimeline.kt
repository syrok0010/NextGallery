package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.domain.media.MediaId

internal data class ViewerTimeline(
    val items: List<MediaItem>,
    val slotIndexByMediaId: Map<MediaId, Int>,
)

@Composable
internal fun rememberViewerTimeline(snapshot: TimelineSnapshot?): ViewerTimeline {
    return remember(snapshot) {
        snapshot.toViewerTimeline()
    }
}

internal fun TimelineSnapshot?.toViewerTimeline(): ViewerTimeline {
    if (this == null) {
        return ViewerTimeline(
            items = emptyList(),
            slotIndexByMediaId = emptyMap(),
        )
    }

    val items = ArrayList<MediaItem>(this.items.size)
    val slotIndexByMediaId = LinkedHashMap<MediaId, Int>()

    slots.forEachIndexed { slotIndex, slot ->
        val item = slot.mediaItem ?: return@forEachIndexed
        items += item
        slotIndexByMediaId[item.mediaId] = slotIndex
    }

    return ViewerTimeline(
        items = items,
        slotIndexByMediaId = slotIndexByMediaId,
    )
}
