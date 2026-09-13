package com.syrok0010.nextgallery.app.ui

import androidx.navigation3.runtime.NavKey
import com.syrok0010.nextgallery.core.session.SessionUiState
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface NextGalleryRoute : NavKey {
    @Serializable
    data object Login : NextGalleryRoute

    @Serializable
    data object Photos : NextGalleryRoute

    @Serializable
    data object Albums : NextGalleryRoute
}

internal fun SessionUiState.rootRoute(): NextGalleryRoute =
    when (this) {
        is SessionUiState.SignedOut -> NextGalleryRoute.Login
        is SessionUiState.SignedIn -> NextGalleryRoute.Photos
    }

internal fun syncedBackStack(
    currentBackStack: List<NavKey>,
    session: SessionUiState,
): List<NextGalleryRoute> {
    val routes = currentBackStack.filterIsInstance<NextGalleryRoute>()
    return when {
        session is SessionUiState.SignedOut -> listOf(NextGalleryRoute.Login)
        routes == listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums) &&
            routes.size == currentBackStack.size -> routes
        else -> listOf(NextGalleryRoute.Photos)
    }
}

internal fun destinationBackStack(destination: NextGalleryRoute): List<NextGalleryRoute> = when (destination) {
    NextGalleryRoute.Login -> listOf(NextGalleryRoute.Login)
    NextGalleryRoute.Photos -> listOf(NextGalleryRoute.Photos)
    NextGalleryRoute.Albums -> listOf(NextGalleryRoute.Photos, NextGalleryRoute.Albums)
}
