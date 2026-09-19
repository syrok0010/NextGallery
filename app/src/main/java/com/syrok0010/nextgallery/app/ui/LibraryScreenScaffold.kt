package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syrok0010.nextgallery.feature.albums.AlbumCatalogRepository
import com.syrok0010.nextgallery.feature.timeline.TimelineRepository
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import org.koin.compose.koinInject

/** Shared presentation subscribes only to the error badge; detailed state is read when the panel opens. */
@Composable
internal fun LibraryScreenScaffold(
    destination: TopLevelDestination,
    onLogout: () -> Unit,
    viewerVisible: Boolean = false,
    timeline: TimelineRepository = koinInject(),
    catalog: AlbumCatalogRepository = koinInject(),
    content: @Composable BoxScope.() -> Unit,
) {
    var diagnostics by rememberSaveable { mutableStateOf(false) }
    val problems = remember(timeline, catalog) {
        combine(timeline.state, catalog.state) { photos, albums ->
            photos.message.error != null || photos.timeline.loadMoreError != null || albums.remote.failed || albums.local.failed
        }.distinctUntilChanged()
    }
    val hasProblem by problems.collectAsState(false)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Box(Modifier
            .alpha(if (viewerVisible) 0f else 1f)
            .then(if (viewerVisible) Modifier.clearAndSetSemantics {} else Modifier)
        ) {
            LibraryHeader(
                destination,
                hasProblem,
                { diagnostics = true },
                { timeline.refresh(); catalog.refresh() },
                onLogout)
        }
        Box(Modifier.weight(1f), content = content)
    }
    if (diagnostics) {
        val photos by timeline.state.collectAsState()
        val albums by catalog.state.collectAsState()
        LibraryDiagnostics(photos, albums, onDismiss = { diagnostics = false })
    }
}
