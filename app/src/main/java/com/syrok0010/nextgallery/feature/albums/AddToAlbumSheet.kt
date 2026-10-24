package com.syrok0010.nextgallery.feature.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import org.koin.compose.koinInject

/** Album picker for a selection snapshot; the caller owns the selection and its lifetime. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddToAlbumSheet(
    request: AlbumAdditionRequest,
    onDismiss: () -> Unit,
    onPick: (AlbumTarget) -> Unit,
    additions: AlbumAdditions = koinInject(),
    catalog: AlbumCatalogRepository = koinInject(),
    sessions: SessionStore = koinInject(),
) {
    val busy = additions.state.collectAsStateWithLifecycle().value is AlbumAdditionState.Running
    val albums by catalog.state.collectAsStateWithLifecycle()
    val session by sessions.session.collectAsStateWithLifecycle()
    val user = (session as? SessionUiState.SignedIn)?.credentials?.userId
    val writable = remember(albums.remote.items, user) {
        user?.let { writableAlbums(albums.remote.items, it) }.orEmpty()
    }
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("add_to_album_sheet"),
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text(
                stringResource(R.string.album_add_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            if (request.fileIds.isEmpty()) {
                Text(
                    stringResource(R.string.album_add_nothing),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.album_add_eligible, request.fileIds.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (request.skippedLocal > 0 && request.fileIds.isNotEmpty()) {
                Text(
                    stringResource(R.string.album_add_skipped_local, request.skippedLocal),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (request.fileIds.isEmpty()) {
            Spacer(Modifier.navigationBarsPadding().height(16.dp))
            return@ModalBottomSheet
        }
        if (albums.remote.loading) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
        if (busy) {
            Text(
                stringResource(R.string.album_add_busy),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (albums.remote.failed) {
            Text(
                stringResource(R.string.albums_remote_error),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        LazyColumn(
            contentPadding = PaddingValues(bottom = 16.dp),
            modifier = Modifier.navigationBarsPadding().testTag("add_to_album_list"),
        ) {
            item {
                AlbumChoiceRow(
                    title = stringResource(R.string.album_add_new),
                    enabled = user != null && !busy,
                    onClick = { creating = true },
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                }
            }
            if (user != null && !albums.remote.loading && writable.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.album_add_list_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            items(writable, key = { it.id }) { album ->
                val location = album.location as AlbumLocation.Remote
                AlbumChoiceRow(
                    title = album.name,
                    subtitle = album.count?.let { stringResource(R.string.albums_count, it) },
                    enabled = !busy,
                    onClick = { onPick(AlbumTarget.Existing(location, album.name)) },
                ) {
                    AlbumCoverImage(album)
                }
            }
        }
    }
    if (creating) {
        NewAlbumDialog(
            existing = remember(writable) { writable.map { it.name }.toSet() },
            onDismiss = { creating = false },
            onCreate = {
                creating = false
                onPick(AlbumTarget.New(it))
            },
        )
    }
}

@Composable
private fun AlbumChoiceRow(
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    leading: @Composable BoxScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
            content = leading,
        )
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

internal enum class AlbumNameProblem { Blank, Slash, Exists }

/** Photos album names are DAV collection names inside the owner's `albums` folder. */
internal fun albumNameProblem(name: String, existing: Set<String>): AlbumNameProblem? {
    val trimmed = name.trim()
    return when {
        trimmed.isEmpty() -> AlbumNameProblem.Blank
        '/' in trimmed -> AlbumNameProblem.Slash
        trimmed in existing -> AlbumNameProblem.Exists
        else -> null
    }
}

@Composable
private fun NewAlbumDialog(
    existing: Set<String>,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    val problem = albumNameProblem(name, existing)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_add_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                label = { Text(stringResource(R.string.album_add_new_name)) },
                isError = problem == AlbumNameProblem.Slash || problem == AlbumNameProblem.Exists,
                supportingText = {
                    when (problem) {
                        AlbumNameProblem.Slash -> Text(
                            stringResource(R.string.album_add_name_invalid),
                        )

                        AlbumNameProblem.Exists -> Text(
                            stringResource(R.string.album_add_name_exists),
                        )

                        else -> {}
                    }
                },
                modifier = Modifier.testTag("new_album_name"),
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim()) }, enabled = problem == null) {
                Text(stringResource(R.string.album_add_create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.album_add_cancel)) }
        },
    )
}
