package com.syrok0010.nextgallery.feature.albums

import androidx.lifecycle.ViewModel

internal class AlbumsViewModel(private val catalog: AlbumCatalogRepository) : ViewModel() {
    val state = catalog.state
    fun refresh() = catalog.refresh()
}
