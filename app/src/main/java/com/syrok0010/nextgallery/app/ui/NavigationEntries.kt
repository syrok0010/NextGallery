package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.navigation3.runtime.NavEntry
import com.syrok0010.nextgallery.feature.albums.AlbumSummary
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.dsl.navigation3.navigation

/** Root-owned presentation and callbacks; destinations do not own the stack or tab state. */
internal data class NavigationUi(
    val screenStates: SaveableStateHolder,
    val logout: () -> Unit,
    val uploads: () -> Unit,
    val album: (AlbumSummary) -> Unit,
    val back: () -> Unit,
    val viewer: (Boolean) -> Unit,
    val selection: (Boolean) -> Unit,
)

internal val LocalNavigationUi =
    staticCompositionLocalOf<NavigationUi> { error("Navigation host required") }

@OptIn(KoinExperimentalAPI::class)
internal fun navigationModule() =
    module {
        navigation<NextGalleryRoute.Uploads>(metadata = subscreenTransitions) {
            val ui = LocalNavigationUi.current
            UploadsScreen(onBack = ui.back)
        }
        navigation<NextGalleryRoute.Photos> {
            val ui = LocalNavigationUi.current
            ui.screenStates.SaveableStateProvider("photos") {
                PhotosScreen(ui.logout, ui.uploads, ui.viewer, ui.selection)
            }
        }
        navigation<NextGalleryRoute.Albums> {
            val ui = LocalNavigationUi.current
            ui.screenStates.SaveableStateProvider("albums") {
                AlbumsScreen(ui.logout, ui.uploads, ui.album)
            }
        }
        navigation<NextGalleryRoute.Album>(metadata = subscreenTransitions) { album ->
            val ui = LocalNavigationUi.current
            AlbumScreen(album, ui.back, ui.logout, ui.uploads)
        }
    }

/** Koin 4.2.2 navigation DSL has no contentKey parameter. Preserve the app's saved-state identity. */
internal fun stableNavigationEntry(
    route: NextGalleryRoute,
    entry: NavEntry<NextGalleryRoute>,
): NavEntry<NextGalleryRoute> =
    NavEntry(
        route,
        contentKey = route.navContentKey(),
        metadata = entry.metadata,
    ) { entry.Content() }

@OptIn(KoinExperimentalAPI::class)
@Composable
internal fun appEntryProvider(): (NextGalleryRoute) -> NavEntry<NextGalleryRoute> {
    val provider = koinEntryProvider<NextGalleryRoute>()
    return { route -> stableNavigationEntry(route, provider(route)) }
}
