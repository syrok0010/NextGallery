package com.syrok0010.nextgallery.feature.library

import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
