package com.syrok0010.nextgallery.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
internal sealed interface NextGalleryRoute : NavKey {
    @Serializable
    data object Login : NextGalleryRoute

    @Serializable
    data object Authenticated : NextGalleryRoute
}

internal fun SessionUiState.rootRoute(): NextGalleryRoute {
    return when (this) {
        is SessionUiState.SignedOut -> NextGalleryRoute.Login
        is SessionUiState.SignedIn -> NextGalleryRoute.Authenticated
    }
}

internal fun syncedBackStack(
    currentBackStack: List<NavKey>,
    session: SessionUiState,
): List<NextGalleryRoute> {
    val requiredRoot = session.rootRoute()
    val typedBackStack = currentBackStack.filterIsInstance<NextGalleryRoute>()
    val currentRoot = typedBackStack.firstOrNull()
    return if (
        currentRoot == requiredRoot &&
        typedBackStack.isNotEmpty() &&
        typedBackStack.size == currentBackStack.size
    ) {
        typedBackStack
    } else {
        listOf(requiredRoot)
    }
}
