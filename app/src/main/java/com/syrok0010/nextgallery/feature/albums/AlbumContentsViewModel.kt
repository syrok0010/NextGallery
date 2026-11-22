package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

internal class AlbumContentsViewModel(
    private val location: AlbumLocation,
    private val albums: Albums,
) : ViewModel() {
    val state: StateFlow<AlbumContentsState> = albums
        .observeContents(location)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumContentsState())

    fun refresh() = albums.refresh(location)
}
