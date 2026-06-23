package com.syrok0010.nextgallery.ui

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import com.syrok0010.nextgallery.ui.auth.LoginScreen
import com.syrok0010.nextgallery.ui.timeline.HomeScreen
import org.koin.androidx.compose.koinViewModel

@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
fun NextGalleryApp(viewModel: SessionViewModel = koinViewModel()) {
    val session by viewModel.session.collectAsState()
    val backStack = rememberNavBackStack(session.rootRoute())
    val viewerTransitionCoordinator = remember { DefaultViewerTransitionCoordinator() }

    LaunchedEffect(session) {
        viewerTransitionCoordinator.onSessionChanged(session)

        val expectedBackStack = syncedBackStack(
            currentBackStack = backStack.toList(),
            session = session,
        )
        if (backStack != expectedBackStack) {
            backStack.clear()
            backStack.addAll(expectedBackStack)
        }
    }

    SharedTransitionLayout {
        NavDisplay(
            backStack = backStack,
            sharedTransitionScope = this,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryProvider = entryProvider {
                entry<NextGalleryRoute.Login> { LoginScreen() }

                entry<NextGalleryRoute.Authenticated> {
                    HomeScreen(
                        viewerTransitionCoordinator = viewerTransitionCoordinator,
                        sharedTransitionScope = this@SharedTransitionLayout,
                        animatedVisibilityScope = LocalNavAnimatedContentScope.current,
                    )
                }
            },
        )
    }
}
