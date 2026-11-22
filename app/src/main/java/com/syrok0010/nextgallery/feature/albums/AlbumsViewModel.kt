package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel

internal class AlbumsViewModel(private val albums: Albums) : ViewModel() {
    val state = albums.state
    fun refresh() = albums.refreshCatalog()
}
