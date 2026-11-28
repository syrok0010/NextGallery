package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.media.MediaId
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
        .runningFold(DepartureTracker()) { tracker, current ->
            val ids = current.items.mapTo(mutableSetOf()) { it.mediaId }
            DepartureTracker(
                previous = ids,
                departed = (tracker.departed + (tracker.previous - ids)) - ids,
            )
        }.map { it.departed }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun refresh() = albums.refresh(location)

    fun prepareAddition(mediaIds: Collection<MediaId>): AlbumAdditionPreparation? =
        albums.prepareAddition(mediaIds)

    fun addToAlbum(target: AlbumTarget, addition: AlbumAdditionPreparation): Boolean =
        albums.add(target, addition)

    fun removeMembers(albumTitle: String, mediaIds: Collection<MediaId>): Boolean {
        val remote = location as? AlbumLocation.Remote ?: return false
        return albums.removeMembers(remote, albumTitle, mediaIds)
    }

    private data class DepartureTracker(
        val previous: Set<MediaId> = emptySet(),
        val departed: Set<MediaId> = emptySet(),
    )
}
