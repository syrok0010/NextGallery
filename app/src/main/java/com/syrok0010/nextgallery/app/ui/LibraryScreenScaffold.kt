package com.syrok0010.nextgallery.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.app.library.MediaLibraryCoordinator
import com.syrok0010.nextgallery.feature.library.LocalLibraryStatus
import com.syrok0010.nextgallery.feature.library.MediaLibraryIndex
import com.syrok0010.nextgallery.router.AppNavigator
import org.koin.compose.koinInject

/** Shared presentation for the library chrome and page content. */
@Composable
internal fun LibraryScreenScaffold(
    destination: TopLevelDestination,
    viewerVisible: Boolean = false,
    onRefresh: () -> Unit = {},
    isSubscreen: Boolean = false,
    title: String? = null,
    navigator: AppNavigator = koinInject(),
    library: MediaLibraryCoordinator = koinInject(),
    libraryIndex: MediaLibraryIndex = koinInject(),
    operations: OperationStatus = rememberOperationStatus(),
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    val logout = LocalSessionLogout.current
    val localStatus by libraryIndex.localStatus.collectAsStateWithLifecycle()
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
                    onRefresh()
                },
                onLogout = {
                    if (!operations.tryLogout(logout)) {
                        Toast
                            .makeText(
                                context,
                                R.string.logout_wait_for_operations,
                                Toast.LENGTH_SHORT,
                            ).show()
                    }
                },
                logoutEnabled = !operations.running,
                onBack = if (isSubscreen) navigator::back else null,
                title = title,
                statusActive = operations.active,
                status = {
                    OperationStatusPill(
                        operations,
                        visible = true,
                    )
                },
            )
        }
        if (!isSubscreen && localStatus.loading) {
            Box(if (viewerVisible) Modifier.clearAndSetSemantics {} else Modifier) {
                LocalIndexingBanner(localStatus)
            }
        }
        Box(Modifier.weight(1f), content = content)
    }
}

@Composable
internal fun LocalIndexingBanner(status: LocalLibraryStatus) {
    if (status.initial) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("local_indexing_banner"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val progress = status.progress
            if (progress != null && progress.totalCount > 0) {
                LinearProgressIndicator(
                    progress = {
                        (progress.indexedCount.toFloat() / progress.totalCount).coerceIn(0f, 1f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local_indexing_progress"),
                )
                Text(
                    stringResource(
                        R.string.local_indexing_initial_progress,
                        progress.indexedCount,
                        progress.totalCount,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local_indexing_progress"),
                )
                Text(
                    stringResource(R.string.local_indexing_initial),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                stringResource(R.string.local_indexing_one_time_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .testTag("local_indexing_banner"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier
                    .size(14.dp)
                    .testTag("local_indexing_spinner"),
                strokeWidth = 2.dp,
            )
            Text(
                stringResource(R.string.local_indexing_refreshing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
