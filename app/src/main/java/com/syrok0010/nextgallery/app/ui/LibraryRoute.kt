package com.syrok0010.nextgallery.app.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

/** Destinations in the signed-in library. Photos is the root for system Back. */
@Serializable
internal sealed interface LibraryRoute : NavKey {
    @Serializable
    data object Photos : LibraryRoute

    @Serializable
    data object Albums : LibraryRoute
}

internal fun libraryBackStack(destination: LibraryRoute): List<LibraryRoute> = when (destination) {
    LibraryRoute.Photos -> listOf(LibraryRoute.Photos)
    LibraryRoute.Albums -> listOf(LibraryRoute.Photos, LibraryRoute.Albums)
}
