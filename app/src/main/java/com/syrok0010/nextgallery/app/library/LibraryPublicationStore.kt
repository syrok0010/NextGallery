package com.syrok0010.nextgallery.app.library

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Application adapter that retains the current execution publication for screens. */
internal class LibraryPublicationStore {
    private val mutableState = MutableStateFlow(LibraryPublication())
    val state = mutableState.asStateFlow()

    fun publish(publication: LibraryPublication) {
        mutableState.value = publication
    }
}
