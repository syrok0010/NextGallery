package com.syrok0010.nextgallery.feature.timeline

import com.syrok0010.nextgallery.core.media.MediaItem

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
    val slots: List<TimelineSlot>,
    val loadedDayIds: Set<Int>,
    val totalMediaCountHint: Int,
) {
    val totalDayCount: Int get() = days.size
    val memoriesVersion: String = config?.version.orEmpty()
    val timelinePath: String? = config?.timelinePath
    val items: List<MediaItem> = slots.mapNotNull { it.mediaItem }
}

data class TimelineDay(
    val dayId: Int,
    val count: Int,
)

data class TimelineSlot(
    val key: TimelineSlotKey,
    val dayId: Int,
    val indexInDay: Int,
    val mediaItem: MediaItem?,
)

data class TimelineSlotKey(
    val dayId: Int,
    val indexInDay: Int,
)
