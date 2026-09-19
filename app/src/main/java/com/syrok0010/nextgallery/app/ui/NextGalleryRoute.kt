package com.syrok0010.nextgallery.app.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import com.syrok0010.nextgallery.R
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

internal enum class TopLevelDestination(
    val route: NextGalleryRoute,
    @get:StringRes val titleRes: Int,
    @get:DrawableRes val iconRes: Int,
) {
    Photos(NextGalleryRoute.Photos, R.string.library_photos, R.drawable.ic_photos),
    Albums(NextGalleryRoute.Albums, R.string.library_albums, R.drawable.ic_albums),
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

        routes.isNotEmpty() &&
            routes.all { it != NextGalleryRoute.Login } &&
            routes.distinct().size == routes.size &&
            routes.size == currentBackStack.size -> routes

        else -> listOf(NextGalleryRoute.Photos)
    }
}

internal fun destinationBackStack(
    currentBackStack: List<NextGalleryRoute>,
    destination: NextGalleryRoute,
): List<NextGalleryRoute> =
    if (destination in currentBackStack) {
        currentBackStack
    } else {
        currentBackStack + destination
    }
