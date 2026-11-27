package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.syrok0010.nextgallery.core.media.MediaId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private val mutableDepartedIds = MutableStateFlow<Set<MediaId>>(emptySet())
    val departedIds: StateFlow<Set<MediaId>> = mutableDepartedIds.asStateFlow()

    init {
        viewModelScope.launch {
            var previous = emptySet<MediaId>()
            state.collect { current ->
                val ids = current.items.mapTo(mutableSetOf()) { it.mediaId }
                mutableDepartedIds.value = (mutableDepartedIds.value + (previous - ids)) - ids
                previous = ids
            }
        }
    }

    fun refresh() = albums.refresh(location)

    fun addToOtherAlbum(target: AlbumTarget, request: AlbumAdditionRequest): Boolean =
        albums.add(target, request)

    fun removeMembers(albumTitle: String, ids: Set<Long>): Boolean =
        (location as? AlbumLocation.Remote)?.let {
            albums.removeMembers(
                it,
                albumTitle,
                ids,
            )
        } == true
}
