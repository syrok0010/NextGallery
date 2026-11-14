package com.syrok0010.nextgallery.feature.collection

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem

internal fun slot(id: String, dayId: Int, indexInDay: Int) =
    MediaSlot(MediaSlotKey(dayId, indexInDay), dayId, indexInDay, media(id, dayId))

/** A slot whose object is not loaded yet. */
internal fun placeholder(dayId: Int, indexInDay: Int) =
    MediaSlot(MediaSlotKey(dayId, indexInDay), dayId, indexInDay, null)

internal fun gridKey(id: String) = "media:$id"

internal fun headerKey(dayId: Int) = "day-header:$dayId"

internal fun ids(vararg names: String) = names.mapTo(HashSet(), ::MediaId)

private fun media(id: String, dayId: Int) =
    MediaItem(
        mediaId = MediaId(id),
        dayId = dayId,
        displayName = "$id.jpg",
        mimeType = "image/jpeg",
        width = 1_024,
        height = 768,
        etag = null,
        livePhotoId = null,
        auid = "auid-$id",
        buid = null,
        sharedBy = null,
        takenAtEpochSeconds = dayId * 86_400L,
        isVideo = false,
        videoDurationSeconds = null,
        isFavorite = false,
        isHidden = false,
        assetRef = MediaAssetRef.MemoriesFile(id.hashCode().toLong()),
    )
