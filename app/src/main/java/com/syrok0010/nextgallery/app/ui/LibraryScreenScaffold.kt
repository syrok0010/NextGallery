package com.syrok0010.nextgallery.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.feature.albums.Albums
import org.koin.compose.koinInject

/** Shared presentation for the library chrome and page content. */
@Composable
internal fun LibraryScreenScaffold(
    destination: TopLevelDestination,
    onLogout: () -> Unit,
    onOpenUploads: () -> Unit,
    viewerVisible: Boolean = false,
    onRefresh: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    title: String? = null,
    library: MediaLibraryCoordinator = koinInject(),
    albums: Albums = koinInject(),
    operations: OperationStatus = rememberOperationStatus(),
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding(),
    ) {
        // Stays as it is under the viewer, which covers it like the rest of the page.
        Box(if (viewerVisible) Modifier.clearAndSetSemantics {} else Modifier) {
            LibraryHeader(
                destination = destination,
                onRefresh = {
                    library.refresh()
                    albums.refresh()
                    onRefresh()
                },
                onLogout = {
                    if (!operations.tryLogout(onLogout)) {
                        Toast
                            .makeText(
                                context,
                                R.string.logout_wait_for_operations,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                },
                logoutEnabled = !operations.running,
                onBack = onBack,
                title = title,
                statusActive = operations.active,
                status = {
                    OperationStatusPill(
                        operations,
                        visible = true,
                        onOpenUploads,
                    )
                },
            )
        }
        Box(Modifier.weight(1f), content = content)
    }
}
