package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository
import com.syrok0010.nextgallery.feature.library.MediaLibraryCommands
import org.koin.compose.koinInject

/** Shared presentation for the library chrome and page content. */
@Composable
internal fun LibraryScreenScaffold(
    destination: TopLevelDestination,
    onLogout: () -> Unit,
    viewerVisible: Boolean = false,
    onRefresh: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    title: String? = null,
    library: MediaLibraryCommands = koinInject(),
    catalog: AlbumCatalogRepository = koinInject(),
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        Box(
            Modifier
                .alpha(if (viewerVisible) 0f else 1f)
                .then(if (viewerVisible) Modifier.clearAndSetSemantics {} else Modifier),
        ) {
            LibraryHeader(
                destination = destination,
                onRefresh = {
                    library.refresh()
                    catalog.refresh()
                    onRefresh()
                },
                onLogout = onLogout,
                onBack = onBack,
                title = title,
            )
        }
        Box(Modifier.weight(1f), content = content)
    }
}
