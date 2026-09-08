package com.syrok0010.nextgallery.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.syrok0010.nextgallery.data.memories.MediaItem
import com.syrok0010.nextgallery.data.memories.TimelineSnapshot
import com.syrok0010.nextgallery.domain.media.MediaId

internal class ViewerSequence(
    val items: List<MediaItem>,
    private val pageIndexByMediaId: Map<MediaId, Int>,
    private val timelineSlotIndexByMediaId: Map<MediaId, Int>,
) {
    operator fun contains(mediaId: MediaId): Boolean = mediaId in pageIndexByMediaId

    fun pageIndex(mediaId: MediaId): Int? = pageIndexByMediaId[mediaId]

    fun timelineSlotIndex(mediaId: MediaId): Int? = timelineSlotIndexByMediaId[mediaId]

    fun pageKey(page: Int): String = items[page].mediaId.value

    fun item(mediaId: MediaId): MediaItem? =
        pageIndexByMediaId[mediaId]?.let(items::get)

    fun retainOrphan(item: MediaItem, page: Int): ViewerSequence {
        check(item.mediaId !in this)
        require(page in 0..items.size)
        val retainedItems = items.toMutableList().apply { add(page, item) }
        val retainedPageIndexes = LinkedHashMap<MediaId, Int>(retainedItems.size)
        retainedItems.forEachIndexed { index, retainedItem ->
            retainedPageIndexes[retainedItem.mediaId] = index
        }
        return ViewerSequence(
            items = retainedItems,
            pageIndexByMediaId = retainedPageIndexes,
            timelineSlotIndexByMediaId = timelineSlotIndexByMediaId,
        )
    }

    companion object {
        val Empty = ViewerSequence(
            items = emptyList(),
            pageIndexByMediaId = emptyMap(),
            timelineSlotIndexByMediaId = emptyMap(),
        )
    }
}

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
    val timelineSlotIndexByMediaId = LinkedHashMap<MediaId, Int>()

    slots.forEachIndexed { slotIndex, slot ->
        val item = slot.mediaItem ?: return@forEachIndexed
        pageIndexByMediaId[item.mediaId] = items.size
        timelineSlotIndexByMediaId[item.mediaId] = slotIndex
        items += item
    }

    return ViewerSequence(
        items = items,
        pageIndexByMediaId = pageIndexByMediaId,
        timelineSlotIndexByMediaId = timelineSlotIndexByMediaId,
    )
}

private fun reconcileCurrentMedia(
    live: ViewerSequence,
    previous: ViewerSequence,
    currentMediaId: MediaId?,
): ViewerSequence {
    if (currentMediaId == null || currentMediaId in live) {
        return live
    }

    val orphan = previous.item(currentMediaId) ?: return live
    val previousPage = checkNotNull(previous.pageIndex(currentMediaId))
    val insertionPage = orphanInsertionPage(
        live = live,
        previous = previous,
        previousPage = previousPage,
    )
    return live.retainOrphan(orphan, insertionPage)
}

private fun orphanInsertionPage(
    live: ViewerSequence,
    previous: ViewerSequence,
    previousPage: Int,
): Int {
    for (page in previousPage + 1..previous.items.lastIndex) {
        live.pageIndex(previous.items[page].mediaId)?.let { return it }
    }
    for (page in previousPage - 1 downTo 0) {
        live.pageIndex(previous.items[page].mediaId)?.let { return it + 1 }
    }

    return previousPage.coerceAtMost(live.items.size)
}
