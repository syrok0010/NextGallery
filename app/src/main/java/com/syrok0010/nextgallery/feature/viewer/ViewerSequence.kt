package com.syrok0010.nextgallery.feature.viewer

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem

internal class ViewerSequence(
    val items: List<MediaItem>,
    private val pageIndexByMediaId: Map<MediaId, Int>,
) {
    operator fun contains(mediaId: MediaId): Boolean = mediaId in pageIndexByMediaId

    fun pageIndex(mediaId: MediaId): Int? = pageIndexByMediaId[mediaId]


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
        )
    }

    companion object {
        val Empty = ViewerSequence(
            items = emptyList(),
            pageIndexByMediaId = emptyMap(),
        )
    }
}

internal fun reconcileCurrentMedia(
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
