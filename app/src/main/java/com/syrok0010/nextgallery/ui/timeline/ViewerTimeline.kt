package com.syrok0010.nextgallery.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot

internal data class ViewerTimeline(
    val items: List<MediaItem>,
    val slotIndexByFileId: Map<Long, Int>,
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
            slotIndexByFileId = emptyMap(),
        )
    }

    val items = ArrayList<MediaItem>(this.items.size)
    val slotIndexByFileId = LinkedHashMap<Long, Int>()

    slots.forEachIndexed { slotIndex, slot ->
        val item = slot.mediaItem ?: return@forEachIndexed
        items += item
        slotIndexByFileId[item.fileId] = slotIndex
    }

    return ViewerTimeline(
        items = items,
        slotIndexByFileId = slotIndexByFileId,
    )
}
