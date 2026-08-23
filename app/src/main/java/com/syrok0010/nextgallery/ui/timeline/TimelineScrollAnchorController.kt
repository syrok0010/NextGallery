package com.syrok0010.nextgallery.ui.timeline

import com.syrok0010.nextgallery.domain.media.MediaId

internal data class TimelineVisibleGridItem(
    val gridIndex: Int,
    val key: String,
    val viewportOffsetPx: Int,
)

internal data class TimelineScrollRestoration(
    val mediaId: MediaId?,
    val gridIndex: Int,
    val scrollOffsetPx: Int,
)

internal class TimelineScrollAnchorController {
    fun restorationForUpdate(
        previousGridItems: List<TimelineGridItem>,
        newGridItems: List<TimelineGridItem>,
        visibleItems: List<TimelineVisibleGridItem>,
        isRestorationAllowed: Boolean,
    ): TimelineScrollRestoration? {
        if (!isRestorationAllowed) {
            return null
        }
        if (visibleItems.isAtTimelineStart()) {
            return TimelineScrollRestoration(
                mediaId = null,
                gridIndex = 0,
                scrollOffsetPx = 0,
            )
        }

        val previousItemsByKey = previousGridItems.associateBy(TimelineGridItem::key)
        val anchor = visibleItems
            .sortedBy(TimelineVisibleGridItem::gridIndex)
            .firstNotNullOfOrNull { visibleItem ->
                val item = previousItemsByKey[visibleItem.key]
                    as? TimelineGridItem.Slot
                item?.slot?.mediaItem?.let { mediaItem ->
                    TimelineScrollAnchor(
                        mediaId = mediaItem.mediaId,
                        canonicalTime = mediaItem.takenAtEpochSeconds
                            ?: (mediaItem.dayId.toLong() * SECONDS_PER_DAY),
                        viewportOffsetPx = visibleItem.viewportOffsetPx,
                    )
                }
            }
            ?: return null
        val target = newGridItems
            .mapIndexedNotNull { gridIndex, item ->
                val mediaItem = (item as? TimelineGridItem.Slot)?.slot?.mediaItem
                    ?: return@mapIndexedNotNull null
                TimelineScrollTargetCandidate(
                    mediaId = mediaItem.mediaId,
                    gridIndex = gridIndex,
                    canonicalTime = mediaItem.takenAtEpochSeconds
                        ?: (mediaItem.dayId.toLong() * SECONDS_PER_DAY),
                )
            }
            .let { candidates ->
                candidates.firstOrNull { it.mediaId == anchor.mediaId }
                    ?: candidates.minByOrNull { it.canonicalTime.distanceTo(anchor.canonicalTime) }
            }
            ?: return null

        return TimelineScrollRestoration(
            mediaId = target.mediaId,
            gridIndex = target.gridIndex,
            scrollOffsetPx = -anchor.viewportOffsetPx,
        )
    }
}

private fun List<TimelineVisibleGridItem>.isAtTimelineStart(): Boolean =
    any { item -> item.gridIndex == 0 && item.viewportOffsetPx >= 0 }

private data class TimelineScrollAnchor(
    val mediaId: MediaId,
    val canonicalTime: Long,
    val viewportOffsetPx: Int,
)

private data class TimelineScrollTargetCandidate(
    val mediaId: MediaId,
    val gridIndex: Int,
    val canonicalTime: Long,
)

private fun Long.distanceTo(other: Long): Long = if (this >= other) this - other else other - this

private const val SECONDS_PER_DAY = 86_400L
