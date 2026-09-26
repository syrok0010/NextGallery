package com.syrok0010.nextgallery.feature.collection

import com.syrok0010.nextgallery.core.media.MediaItem

data class MediaSlot(
    val key: MediaSlotKey,
    val dayId: Int,
    val indexInDay: Int,
    val mediaItem: MediaItem?,
)

data class MediaSlotKey(val dayId: Int, val indexInDay: Int)
