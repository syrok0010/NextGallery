package com.syrok0010.nextgallery.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.core.session.SessionUiState
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.feature.auth.LoginScreen
import com.syrok0010.nextgallery.feature.timeline.LocalMediaPermissionFlow
import org.koin.androidx.compose.koinViewModel

private fun routeFromContentKey(contentKey: Any): NextGalleryRoute? =
    when (contentKey) {
        NextGalleryRoute.Login.toString() -> NextGalleryRoute.Login
        NextGalleryRoute.Photos.toString() -> NextGalleryRoute.Photos
        NextGalleryRoute.Albums.toString() -> NextGalleryRoute.Albums
        else -> null
    }

private fun transitionDirection(
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

@Composable
internal fun NextGalleryApp(sessionViewModel: SessionViewModel = koinViewModel()) {
    val session by sessionViewModel.session.collectAsState()
    val backStack = rememberNavBackStack(session.rootRoute())
    val routes = syncedBackStack(backStack.toList(), session)
    val page = routes.last()
    val signedIn = session is SessionUiState.SignedIn
    var viewerVisible by remember(session) { mutableStateOf(false) }
    // Tab state survives popping Albums, but never crosses the session boundary.
    val screenStates = key(session) { rememberSaveableStateHolder() }
    LaunchedEffect(session) {
        if (backStack.toList() != routes) {
            backStack.clear()
            backStack.addAll(routes)
        }
    }
    LocalMediaPermissionFlow(isSignedIn = signedIn)
    if (signedIn) LibrarySystemBars()
    NextGalleryTheme(darkTheme = signedIn || isSystemInDarkTheme(), dynamicColor = !signedIn) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                NavDisplay(
                    backStack = routes,
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    transitionSpec = {
                        horizontalTransition(
                            transitionDirection(
                                routeFromContentKey(initialState.entries.last().contentKey),
                                routeFromContentKey(targetState.entries.last().contentKey),
                            ),
                        )
                    },
                    popTransitionSpec = {
                        horizontalTransition(
                            transitionDirection(
                                routeFromContentKey(initialState.entries.last().contentKey),
                                routeFromContentKey(targetState.entries.last().contentKey),
                            ),
                        )
                    },
                    predictivePopTransitionSpec = {
                        horizontalTransition(
                            transitionDirection(
                                routeFromContentKey(initialState.entries.last().contentKey),
                                routeFromContentKey(targetState.entries.last().contentKey),
                            ),
                        )
                    },
                    entryProvider = entryProvider {
                        entry<NextGalleryRoute.Login> { LoginScreen() }
                        entry<NextGalleryRoute.Photos> {
                            if (signedIn) {
                                screenStates.SaveableStateProvider("photos") {
                                    PhotosScreen(
                                        onLogout = sessionViewModel::logout,
                                        onViewerVisibilityChanged = { viewerVisible = it },
                                    )
                                }
                            }
                        }
                        entry<NextGalleryRoute.Albums> {
                            if (signedIn) {
                                screenStates.SaveableStateProvider("albums") {
                                    AlbumsScreen(
                                        onLogout = sessionViewModel::logout,
                                    )
                                }
                            }
                        }
                    },
                )
                if (signedIn && !viewerVisible) {
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
                        Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(bottom = 16.dp),
                    )
                }
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
