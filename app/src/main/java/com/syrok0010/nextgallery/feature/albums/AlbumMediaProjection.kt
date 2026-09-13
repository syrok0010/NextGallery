package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaAssetRef
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.media.localCopy
import com.syrok0010.nextgallery.core.media.remoteCopy

/** Album membership selects objects; the library supplies their known metadata and available copies. */
internal fun albumMediaProjection(
    members: List<MediaItem>,
    library: Map<MediaId, MediaItem>,
    allowLocal: Boolean,
): List<MediaItem> = members.map { member ->
    val known = library[member.mediaId]
    val remote = member.remoteCopy ?: known?.remoteCopy
    val local = if (allowLocal) member.localCopy ?: known?.localCopy else null
    val canonical = when {
        member.remoteCopy != null -> member
        known?.remoteCopy != null -> known
        known != null && known.localCopy?.modifiedAtEpochSeconds == member.localCopy?.modifiedAtEpochSeconds -> known
        else -> member
    }
    canonical.copy(assetRef = when {
        local != null && remote != null -> MediaAssetRef.LocalFirst(local, remote)
        remote != null -> remote
        local != null -> local
        else -> member.assetRef
    })
}.distinctBy { it.mediaId }.sortedWith(
    compareByDescending<MediaItem> { it.dayId }.thenByDescending { it.takenAtEpochSeconds }.thenBy { it.mediaId.value })
