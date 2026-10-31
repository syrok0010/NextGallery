package com.syrok0010.nextgallery.app.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.syrok0010.nextgallery.R

@Composable
internal fun LibraryHeader(
    destination: TopLevelDestination,
    onRefresh: () -> Unit,
    onLogout: () -> Unit,
    onBack: (() -> Unit)? = null,
    title: String? = null,
    /** While [statusActive], takes the place of the app name, between the logo and the menu. */
    statusActive: Boolean = false,
    status: @Composable () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 18.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        stringResource(R.string.action_back),
                    )
                }
            } else {
                AppLogo()
            }
            Crossfade(
                targetState = statusActive,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
                label = "header_status",
            ) { active ->
                when {
                    active -> status()
                    onBack == null -> Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.library_menu))
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = null,
                            )
                        },
                        text = { Text(stringResource(R.string.action_refresh)) },
                        onClick = { menu = false; onRefresh() },
                    )
                    DropdownMenuItem(
                        leadingIcon = {
                            Icon(
                                Icons.AutoMirrored.Filled.ExitToApp,
                                contentDescription = null,
                            )
                        },
                        text = { Text(stringResource(R.string.action_logout)) },
                        onClick = { menu = false; onLogout() },
                    )
                }
            }
        }
        Text(
            title ?: stringResource(destination.titleRes),
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
        )
    }
}

/** The launcher icon, as wide as the menu button on the other side so the status stays centered. */
@Composable
private fun AppLogo() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(30.dp).clip(CircleShape)) {
            Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize())
            Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
        }
    }
}

@Composable
internal fun LibraryIsland(
    page: NextGalleryRoute,
    onPage: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier.testTag("library_island"),
        shape = RoundedCornerShape(32.dp),
        shadowElevation = 10.dp,
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .97f),
    ) {
        Row(Modifier.padding(5.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TopLevelDestination.entries.forEach { item ->
                val selected = page == item.route
                val text = stringResource(item.titleRes)
                Surface(
                    onClick = { onPage(item) },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .testTag("library_page:$item")
                        .semantics { this.selected = selected },
                ) {
                    Row(
                        Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            painterResource(item.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(text, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
    }
}
