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
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                NavDisplay(
                    backStack = routes,
                    onBack = { if (backStack.size > 1) backStack.removeLastOrNull() },
                    transitionSpec = {
                        if (initialState.entries.last().contentKey == NextGalleryRoute.Photos.toString() &&
                            targetState.entries.last().contentKey == NextGalleryRoute.Albums.toString()) {
                            slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300)) togetherWith
                                slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Left, tween(300))
                        } else fadeIn(tween(200)) togetherWith fadeOut(tween(200))
                    },
                    popTransitionSpec = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) togetherWith
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    },
                    predictivePopTransitionSpec = {
                        slideIntoContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300)) togetherWith
                            slideOutOfContainer(AnimatedContentTransitionScope.SlideDirection.Right, tween(300))
                    },
                    entryProvider = entryProvider {
                        entry<NextGalleryRoute.Login> { LoginScreen() }
                        entry<NextGalleryRoute.Photos> {
                            screenStates.SaveableStateProvider("photos") {
                                PhotosScreen(onLogout = sessionViewModel::logout, onViewerVisibilityChanged = { viewerVisible = it })
                            }
                        }
                        entry<NextGalleryRoute.Albums> {
                            screenStates.SaveableStateProvider("albums") { AlbumsScreen(onLogout = sessionViewModel::logout) }
                        }
                    },
                )
                if (signedIn && !viewerVisible) {
                    LibraryIsland(page, { destination ->
                        val target = destinationBackStack(destination)
                        if (backStack.toList() != target) {
                            if (destination == NextGalleryRoute.Photos) backStack.removeLastOrNull()
                            else backStack.add(NextGalleryRoute.Albums)
                        }
                    }, Modifier.align(Alignment.BottomCenter).safeDrawingPadding().padding(bottom = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun LibrarySystemBars() {
    val view = LocalView.current
    DisposableEffect(view) {
        val activity = generateSequence<Context>(view.context) { (it as? ContextWrapper)?.baseContext }
            .filterIsInstance<Activity>().firstOrNull()
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
