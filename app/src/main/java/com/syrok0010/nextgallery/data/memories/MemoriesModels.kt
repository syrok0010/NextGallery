package com.syrok0010.nextgallery.data.memories

import java.time.LocalDate

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
    val config: MemoriesConfig,
    val days: List<TimelineDay>,
    val slots: List<TimelineSlot>,
    val loadedDayIds: Set<Int>,
    val totalDayCount: Int,
    val totalMediaCountHint: Int,
) {
    val memoriesVersion: String = config.version
    val timelinePath: String? = config.timelinePath
    val items: List<MediaItem> = slots.mapNotNull { it.mediaItem }

    fun mergeLoadedItems(
        items: List<MediaItem>,
        loadedDayIds: Set<Int>,
    ): TimelineSnapshot {
        val currentItemsByDay = slots
            .mapNotNull { it.mediaItem }
            .groupBy { it.dayId }
        val incomingItemsByDay = items.groupBy { it.dayId }
        val mergedItemsByDay = currentItemsByDay + incomingItemsByDay

        return copy(
            slots = buildTimelineSlots(days, mergedItemsByDay),
            loadedDayIds = this.loadedDayIds + loadedDayIds,
        )
    }
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

data class MediaItem(
    val fileId: Long,
    val dayId: Int,
    val day: LocalDate,
    val displayName: String,
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val etag: String?,
    val livePhotoId: String?,
    val auid: String?,
    val buid: String?,
    val sharedBy: String?,
    val takenAtEpochSeconds: Long?,
    val isVideo: Boolean,
    val videoDurationSeconds: Long?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val assetRef: MediaAssetRef,
)

sealed interface MediaAssetRef {
    data class MemoriesFile(
        val photoFileId: Long,
    ) : MediaAssetRef
}

fun buildTimelineSlots(
    days: List<TimelineDay>,
    itemsByDay: Map<Int, List<MediaItem>>,
): List<TimelineSlot> {
    return days.flatMap { day ->
        val items = itemsByDay[day.dayId].orEmpty()
        val slotCount = maxOf(day.count, items.size)

        List(slotCount) { index ->
            TimelineSlot(
                key = TimelineSlotKey(dayId = day.dayId, indexInDay = index),
                dayId = day.dayId,
                indexInDay = index,
                mediaItem = items.getOrNull(index),
            )
        }
    }
}
