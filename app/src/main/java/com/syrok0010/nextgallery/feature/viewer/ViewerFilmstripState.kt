package com.syrok0010.nextgallery.feature.viewer

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Interaction belongs to the viewer; the row and its cancellable frame loaders may disappear. */
internal class ViewerFilmstripState(
    val lazyListState: LazyListState,
    private val scope: CoroutineScope,
) {
    var items by mutableStateOf<List<MediaItem>>(emptyList())
        private set
    var currentMediaId by mutableStateOf<MediaId?>(null)
        private set
    var expandedVideo by mutableStateOf<MediaId?>(null)
        private set
    private var expansionSelection by mutableStateOf<MediaId?>(null)
    private var itemsByKey by mutableStateOf<Map<String, MediaItem>>(emptyMap())
    private var onSelected: (MediaId) -> Unit = {}

    val scrollState = ScrollableState { lazyListState.dispatchRawDelta(it) }
    val isScrolling get() = scrollState.isScrollInProgress
    val expanded get() = expandedVideo != null
    val activeSizeMediaId get() = expansionSelection ?: currentMediaId
    val currentPage get() = items.indexOfFirst { it.mediaId == currentMediaId }

    fun update(items: List<MediaItem>, currentMediaId: MediaId?, onSelected: (MediaId) -> Unit) {
        if (this.items != items) {
            this.items = items
            itemsByKey = items.associateBy { filmstripKey(it.mediaId) }
        }
        this.currentMediaId = currentMediaId
        this.onSelected = onSelected
        if (expandedVideo?.let { itemsByKey[filmstripKey(it)] }?.isVideo != true) {
            expandedVideo = null
            expansionSelection = null
        } else if (expansionSelection?.let { filmstripKey(it) } !in itemsByKey) {
            expansionSelection = null
        }
    }

    fun select(id: MediaId) {
        if (filmstripKey(id) in itemsByKey && id != currentMediaId) onSelected(id)
    }

    fun click(id: MediaId) {
        val item = itemsByKey[filmstripKey(id)] ?: return
        if (item.isVideo) {
            if (expandedVideo == id) {
                expandedVideo = null
                expansionSelection = null
            } else {
                if (!expanded) expansionSelection = currentMediaId
                expandedVideo = id
            }
        }
        select(id)
    }

    // Layout keys, unlike indices, still name the same objects while a new order remeasures.
    fun mediaAtPlayhead(): MediaId? {
        val layout = lazyListState.layoutInfo
        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
        val nearest = layout.visibleItemsInfo
            .filter { info ->
                info.key in itemsByKey
            }.minByOrNull {
                if (it.key == expandedVideo?.let(::filmstripKey)) {
                    when {
                        center < it.offset -> it.offset - center
                        center > it.offset + it.size -> center - it.offset - it.size
                        else -> 0
                    }
                } else {
                    abs(it.offset + it.size / 2 - center)
                }
            } ?: return null
        return itemsByKey[nearest.key]?.mediaId
    }

    fun fraction(id: MediaId): Float {
        val layout = lazyListState.layoutInfo
        val info = layout.visibleItemsInfo.firstOrNull { it.key == filmstripKey(id) } ?: return 0f
        val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
        return ((center - info.offset) / info.size.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    fun movePlayhead(id: MediaId, fraction: Float) {
        scope.launch {
            val index = items.indexOfFirst { it.mediaId == id }
            val layout = lazyListState.layoutInfo
            val info = layout.visibleItemsInfo.firstOrNull { it.key == filmstripKey(id) }
            if (index < 0 || info == null) return@launch
            val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            lazyListState.scrollToItem(index, (info.size * fraction - center).toInt())
        }
    }
}

internal fun filmstripKey(id: MediaId) = "filmstrip:${id.value}"

@Composable
internal fun rememberViewerFilmstripState(
    items: List<MediaItem>,
    currentMediaId: MediaId?,
    onSelected: (MediaId) -> Unit,
    lazyListState: LazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = items
            .indexOfFirst { it.mediaId == currentMediaId }
            .coerceAtLeast(
                0,
            ),
    ),
): ViewerFilmstripState {
    val scope = rememberCoroutineScope()
    val state = remember(lazyListState, scope) { ViewerFilmstripState(lazyListState, scope) }
    SideEffect { state.update(items, currentMediaId, onSelected) }
    LaunchedEffect(state) {
        snapshotFlow { if (state.isScrolling) state.mediaAtPlayhead() else null }
            .collect { it?.let(state::select) }
    }
    LaunchedEffect(state, currentMediaId, items, state.isScrolling, state.expanded) {
        if (!state.isScrolling && !state.expanded && state.currentPage >= 0) {
            lazyListState.animateScrollToItem(state.currentPage)
        }
    }
    return state
}
