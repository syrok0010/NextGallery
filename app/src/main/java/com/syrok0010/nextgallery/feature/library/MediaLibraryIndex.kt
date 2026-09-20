package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.ui.uiText
import com.syrok0010.nextgallery.feature.timeline.TimelineScreenState
import com.syrok0010.nextgallery.feature.timeline.TimelineSnapshot
import com.syrok0010.nextgallery.feature.timeline.TimelineUiState
import com.syrok0010.nextgallery.feature.timeline.local.LocalMediaPermissionMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class MediaIndexingState(
    val snapshot: TimelineSnapshot? = null,
    val loadingDayIds: Set<Int> = emptySet(),
    val failedDayIds: Set<Int> = emptySet(),
    val remote: IndexingOperation = IndexingOperation.Idle,
    val local: IndexingOperation = IndexingOperation.Idle,
    val permission: LocalMediaPermissionMode? = null,
    val lastLocalProgress: IndexingOperation.Indexing? = null,
)

/** Read-only library publication. Missing items may be unhydrated, not deleted.
 * Source caches persist metadata; this session projection is rebuilt from them on startup.
 */
internal class MediaLibraryIndex {
    private val mutableState = MutableStateFlow<Map<MediaId, MediaItem>>(emptyMap())
    val state = mutableState.asStateFlow()

    internal fun publish(items: List<MediaItem>) {
        mutableState.value = items.associateBy { it.mediaId }
    }

    internal fun clear() {
        mutableState.value = emptyMap()
    }
}

internal class TimelineProjectionStore {
    private val mutableState = MutableStateFlow(TimelineScreenState())
    val state = mutableState.asStateFlow()

    internal fun publish(update: MediaIndexingState) {
        val failed = update.failedDayIds
        mutableState.value = TimelineScreenState(
            timeline = TimelineUiState(
                update.snapshot,
                update.loadingDayIds,
                failed,
                if (failed.isEmpty()) null else uiText(R.string.error_load_timeline_batch_failed),
            ),
        )
    }
    internal fun clear() {
        mutableState.value = TimelineScreenState()
    }
}
