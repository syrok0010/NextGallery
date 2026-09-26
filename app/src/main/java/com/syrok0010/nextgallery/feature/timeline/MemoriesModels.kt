package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.feature.collection.MediaSlot

data class MemoriesConfig(
    val version: String,
    val timelinePath: String?,
    val albumsEnabled: Boolean,
    val recognizeEnabled: Boolean,
    val faceRecognitionEnabled: Boolean,
    val previewGeneratorEnabled: Boolean,
    val stackRawFiles: Boolean,
    val dedupIdentical: Boolean,
)

data class TimelineSnapshot(
    val config: MemoriesConfig?,
    val days: List<TimelineDay>,
    val slots: List<MediaSlot>,
    val loadedDayIds: Set<Int>,
    val totalMediaCountHint: Int,
) {
    val items: List<MediaItem> = slots.mapNotNull { it.mediaItem }
}

data class TimelineDay(val dayId: Int, val count: Int)
