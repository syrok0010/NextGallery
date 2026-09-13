package com.syrok0010.nextgallery.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.asString
import com.syrok0010.nextgallery.feature.albums.AlbumsUiState
import com.syrok0010.nextgallery.feature.timeline.AuthenticatedUiState

internal enum class LibraryPage { Photos, Albums }

@Composable
internal fun LibraryHeader(page: LibraryPage, hasProblem: Boolean, onDiagnostics: () -> Unit,
    onRefresh: () -> Unit, onLogout: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.labelLarge)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("library_menu")) {
                    BadgedBox(badge = { if (hasProblem) Badge() }) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.library_menu))
                    }
                }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_diagnostics)) }, onClick = { menu = false; onDiagnostics() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_refresh)) }, onClick = { menu = false; onRefresh() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_logout)) }, onClick = { menu = false; onLogout() })
                }
            }
        }
        Text(stringResource(if (page == LibraryPage.Photos) R.string.library_photos else R.string.library_albums),
            style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(top = 8.dp, bottom = 12.dp))
        if (hasProblem) TextButton(onClick = onDiagnostics, contentPadding = PaddingValues(0.dp)) {
            Text(stringResource(R.string.library_problem), color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun LibraryIsland(page: LibraryPage, onPage: (LibraryPage) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.testTag("library_island"), shape = RoundedCornerShape(32.dp), shadowElevation = 10.dp,
        tonalElevation = 4.dp, color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .97f)) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LibraryPage.entries.forEach { item ->
                val selected = page == item
                val text = stringResource(if (item == LibraryPage.Photos) R.string.library_photos else R.string.library_albums)
                Surface(onClick = { onPage(item) }, shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("library_page:$item").semantics { this.selected = selected }) {
                    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 22.dp, vertical = 15.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryDiagnostics(state: AuthenticatedUiState, albums: AlbumsUiState, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SelectionContainer {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).testTag("library_diagnostics"),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.library_diagnostics), style = MaterialTheme.typography.titleLarge)
                state.timeline.snapshot?.let { snapshot ->
                    Text(stringResource(R.string.timeline_summary, snapshot.memoriesVersion, snapshot.totalMediaCountHint, snapshot.totalDayCount))
                    snapshot.timelinePath?.let { Text(it) }
                    Text(stringResource(R.string.status_loaded_items, snapshot.items.size))
                    Text(stringResource(R.string.diagnostics_days, snapshot.loadedDayIds.size, snapshot.totalDayCount))
                }
                state.sourceDiagnostics.forEach { Text(it.asString()) }
                state.message.status?.takeUnless { message ->
                    message in state.sourceDiagnostics || (state.timeline.snapshot != null &&
                        (message as? com.syrok0010.nextgallery.core.ui.UiText.Resource)?.id == R.string.status_loaded_items)
                }?.let { Text(it.asString()) }
                state.message.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                Text(stringResource(R.string.diagnostics_permission, state.localMediaPermissionMode?.name ?: "—"))
                if (state.timeline.loadingDayIds.isNotEmpty()) {
                    Text(stringResource(R.string.status_loading_timeline_batch))
                    Text(stringResource(R.string.diagnostics_day_ids, state.timeline.loadingDayIds.sorted().joinToString()))
                }
                state.timeline.loadMoreError?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
                if (state.timeline.failedDayIds.isNotEmpty()) Text(stringResource(R.string.diagnostics_failed_days, state.timeline.failedDayIds.sorted().joinToString()))
                HorizontalDivider()
                Text(stringResource(R.string.library_albums), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.diagnostics_catalog, albums.remote.items.size, albums.local.items.size))
                if (albums.remote.loading) Text(stringResource(R.string.albums_loading_cloud))
                if (albums.local.loading) Text(stringResource(R.string.albums_loading_local))
                if (albums.remote.failed) Text(stringResource(R.string.albums_remote_error), color = MaterialTheme.colorScheme.error)
                if (albums.local.failed) Text(stringResource(R.string.albums_local_error), color = MaterialTheme.colorScheme.error)
                if (!albums.remote.supported) Text(stringResource(R.string.albums_unsupported))
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
