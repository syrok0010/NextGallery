package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.media.MediaId
import com.syrok0010.nextgallery.core.media.MediaItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

internal class AlbumContentsViewModel(
    private val location: AlbumLocation,
    private val albums: Albums,
) : ViewModel() {
    val state: StateFlow<AlbumContentsState> = albums
        .observeContents(location)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumContentsState())
    val canModify: Boolean = albums.canModify(location)
    val supportsActions: Boolean = location is AlbumLocation.Folder || canModify
    val removing: StateFlow<Boolean> = albums.removing
    val departedIds: StateFlow<Set<MediaId>> = state
        .runningFold(emptySet<MediaId>() to emptySet<MediaId>()) { (previous, departed), current ->
            val ids = current.items.mapTo(mutableSetOf()) { it.mediaId }
            ids to (departed + (previous - ids)) - ids
        }.map { (_, departed) -> departed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun refresh() = albums.refresh(location)

    fun addToAlbum(target: AlbumTarget, request: AlbumAdditionRequest): Boolean =
        albums.add(target, request)

    fun removeMembers(albumTitle: String, items: List<MediaItem>): Boolean {
        val remote = location as? AlbumLocation.Remote ?: return false
        val ids = items.mapNotNull { it.remoteFileId }.toSet()
        return albums.removeMembers(remote, albumTitle, ids)
    }
}
