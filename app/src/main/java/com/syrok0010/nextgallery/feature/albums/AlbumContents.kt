package com.syrok0010.nextgallery.feature.albums

import com.syrok0010.nextgallery.core.media.MediaItem
import com.syrok0010.nextgallery.core.session.AccountCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Source address, independent of display labels and catalog ordering. */
@Serializable
internal sealed interface AlbumLocation {
    @Serializable data class Remote(val clusterId: String) : AlbumLocation
    @Serializable data class Folder(val volume: String, val path: String) : AlbumLocation
}

internal data class AlbumContentsBatch(val items: List<MediaItem>, val total: Int, val loaded: Int = items.size)
internal fun interface AlbumContentsSource {
    fun load(location: AlbumLocation, credentials: AccountCredentials): Flow<AlbumContentsBatch>
}
