package com.syrok0010.nextgallery.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.di.AuthenticatedGraph
import com.syrok0010.nextgallery.feature.auth.LoginScreen
import com.syrok0010.nextgallery.feature.timeline.LocalMediaPermissionFlow
import com.syrok0010.nextgallery.feature.upload.LogoutRefusal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.compose.scope.UnboundKoinScope
import org.koin.core.annotation.KoinDelicateAPI
import org.koin.core.annotation.KoinExperimentalAPI

internal fun transitionDirection(
    from: NextGalleryRoute?,
    to: NextGalleryRoute?,
): AnimatedContentTransitionScope.SlideDirection? {
    val fromPosition = TopLevelDestination.entries.firstOrNull { it.route == from }?.position
    val toPosition = TopLevelDestination.entries.firstOrNull { it.route == to }?.position
    return when {
        fromPosition == null || toPosition == null -> null
        toPosition > fromPosition -> AnimatedContentTransitionScope.SlideDirection.Left
        toPosition < fromPosition -> AnimatedContentTransitionScope.SlideDirection.Right
        else -> null
    }
}

private fun AnimatedContentTransitionScope<*>.horizontalTransition(
    direction: AnimatedContentTransitionScope.SlideDirection?,
) = direction?.let {
    slideIntoContainer(it, tween(300)) togetherWith slideOutOfContainer(it, tween(300))
} ?: (fadeIn(tween(200)) togetherWith fadeOut(tween(200)))

/**
 * A screen opened over a tab slides in from the end and leaves the same way. During the back
 * gesture it follows the finger and uncovers the tab.
 */
private val subscreenTransitions =
    NavDisplay.transitionSpec {
        horizontalTransition(AnimatedContentTransitionScope.SlideDirection.Left)
    } +
        NavDisplay.popTransitionSpec {
            horizontalTransition(AnimatedContentTransitionScope.SlideDirection.Right)
        } +
        NavDisplay.predictivePopTransitionSpec {
            horizontalTransition(AnimatedContentTransitionScope.SlideDirection.Right)
        }

@OptIn(KoinExperimentalAPI::class, KoinDelicateAPI::class)
@Composable
internal fun NextGalleryApp(
    links: Flow<Uri> = emptyFlow(),
    sessionViewModel: SessionViewModel = koinViewModel(),
    graph: AuthenticatedGraph = koinInject(),
) {
    val session by sessionViewModel.session.collectAsState()
    val logoutRefusal by sessionViewModel.logoutRefusal.collectAsState()
    logoutRefusal?.let { refusal ->
        AlertDialog(
            onDismissRequest = { sessionViewModel.logoutRefusal.value = null },
            text = {
                Text(
                    stringResource(
                        when (refusal) {
                            LogoutRefusal.UnfinishedWork -> R.string.uploads_logout_blocked
                            LogoutRefusal.Failed -> R.string.logout_failed
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { sessionViewModel.logoutRefusal.value = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }
    val signedIn = session is SessionUiState.SignedIn
    NextGalleryTheme(darkTheme = signedIn || isSystemInDarkTheme(), dynamicColor = !signedIn) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                Crossfade(
                    targetState = signedIn,
                    animationSpec = tween(200),
                    label = "sessionBoundary",
                ) { isSignedIn ->
                    if (!isSignedIn) {
                        LoginScreen()
                    } else {
                        UnboundKoinScope(graph.requireScope()) {
                            AuthenticatedApp(
                                links = links,
                                onLogout = sessionViewModel::logout,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthenticatedApp(links: Flow<Uri>, onLogout: () -> Unit) {
    val backStack = rememberNavBackStack(NextGalleryRoute.Photos)
    val routes = normalizedBackStack(backStack.toList())
    val page = routes.last()
    var viewerVisible by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    val screenStates = rememberSaveableStateHolder()
    LaunchedEffect(routes) {
        if (backStack.toList() != routes) {
            backStack.clear()
            backStack.addAll(routes)
        }
    }
    val open = { route: NextGalleryRoute ->
        if (backStack.lastOrNull() != route) backStack.add(route)
    }
    val openUploads = { open(NextGalleryRoute.Uploads) }
    LaunchedEffect(links) {
        links.collect { link -> linkRoute(link)?.let(open) }
    }
    LibrarySystemBars()
    LocalMediaPermissionFlow(isSignedIn = true)
    Box(Modifier.fillMaxSize()) {
        NavDisplay(
            backStack = routes,
            onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            transitionSpec = {
                horizontalTransition(
                    transitionDirection(
                        topLevelRouteFromContentKey(initialState.entries.last().contentKey),
                        topLevelRouteFromContentKey(targetState.entries.last().contentKey),
                    ),
                )
            },
            popTransitionSpec = {
                horizontalTransition(
                    transitionDirection(
                        topLevelRouteFromContentKey(initialState.entries.last().contentKey),
                        topLevelRouteFromContentKey(targetState.entries.last().contentKey),
                    ),
                )
            },
            predictivePopTransitionSpec = {
                horizontalTransition(
                    transitionDirection(
                        topLevelRouteFromContentKey(initialState.entries.last().contentKey),
                        topLevelRouteFromContentKey(targetState.entries.last().contentKey),
                    ),
                )
            },
            entryProvider = entryProvider {
                entry<NextGalleryRoute.Uploads>(
                    clazzContentKey = { it.navContentKey() },
                    metadata = subscreenTransitions,
                ) {
                    UploadsScreen(onBack = { backStack.removeLastOrNull() })
                }
                entry<NextGalleryRoute.Photos>(
                    clazzContentKey = { it.navContentKey() },
                ) {
                    screenStates.SaveableStateProvider("photos") {
                        PhotosScreen(
                            onLogout = onLogout,
                            onOpenUploads = openUploads,
                            onViewerVisibilityChanged = { viewerVisible = it },
                            onSelectionModeChanged = { selectionMode = it },
                        )
                    }
                }
                entry<NextGalleryRoute.Album>(
                    clazzContentKey = { it.navContentKey() },
                    metadata = subscreenTransitions,
                ) { album ->
                    AlbumScreen(
                        album,
                        onBack = { backStack.removeLastOrNull() },
                        onLogout = onLogout,
                        onOpenUploads = openUploads,
                    )
                }
                entry<NextGalleryRoute.Albums>(
                    clazzContentKey = { it.navContentKey() },
                ) {
                    screenStates.SaveableStateProvider("albums") {
                        AlbumsScreen(
                            onLogout = onLogout,
                            onOpenUploads = openUploads,
                            onOpen = { album ->
                                album.location?.let { location ->
                                    if (backStack.lastOrNull() == NextGalleryRoute.Albums) {
                                        backStack.add(NextGalleryRoute.Album(location, album.name))
                                    }
                                }
                            },
                        )
                    }
                }
            },
        )
        if (!selectionMode && page !is NextGalleryRoute.Album && page != NextGalleryRoute.Uploads) {
            // It floats over the pages and so over the viewer too: it steps down out of
            // the way while the viewer opens, and comes back once it has closed.
            AnimatedVisibility(
                visible = !viewerVisible,
                enter = slideInVertically(
                    tween(IslandMoveMillis),
                ) { it } +
                    fadeIn(tween(IslandMoveMillis)),
                exit = slideOutVertically(
                    tween(IslandMoveMillis),
                ) { it } +
                    fadeOut(tween(IslandMoveMillis)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .safeDrawingPadding()
                    .padding(bottom = 16.dp),
            ) {
                LibraryIsland(
                    page,
                    { destination ->
                        val target = destinationBackStack(
                            backStack.toList().filterIsInstance<NextGalleryRoute>(),
                            destination.route,
                        )
                        if (backStack.toList() != target) {
                            backStack.clear()
                            backStack.addAll(target)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun LibrarySystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>()
            .firstOrNull()
        val controller = activity?.let { WindowCompat.getInsetsController(it.window, view) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            oldStatus?.let { controller.isAppearanceLightStatusBars = it }
            oldNavigation?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
}

/** About as long as the viewer takes to open. */
private const val IslandMoveMillis = 220
