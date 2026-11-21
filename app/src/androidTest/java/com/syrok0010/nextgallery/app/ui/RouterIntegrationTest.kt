package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.espresso.Espresso
import com.syrok0010.nextgallery.AppLinks
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.di.ProductionGraph
import com.syrok0010.nextgallery.router.AppNavigator
import com.syrok0010.nextgallery.router.NextGalleryRoute
import com.syrok0010.nextgallery.string
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class RouterIntegrationTest {
    @get:Rule val rule = createComposeRule()

    @Test fun linkWaitsForLoginAndWarmLinksDoNotDuplicateOrReplayAfterRestoration() {
        val graph = ProductionGraph()
        val navigator = graph.koin.get<AppNavigator>()
        val mounted = mutableStateOf(true)
        try {
            navigator.follow(AppLinks.Uploads)
            val restoration = StateRestorationTester(rule)
            restoration.setContent { if (mounted.value) graph.Content { NextGalleryApp() } }
            rule.onNodeWithText(string(R.string.login_title)).assertIsDisplayed()
            assertEquals(1, navigator.commands.value.size)
            rule.runOnIdle {
                graph.koin.get<SessionStore>().signIn(
                    AccountCredentials("http://127.0.0.1:9", "fixture", "unused", "fixture"),
                )
            }
            rule.waitUntil(10_000) {
                rule
                    .onAllNodesWithText(
                        string(R.string.uploads_title),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            }
            rule.waitForIdle()
            if (rule
                    .onAllNodesWithText(
                        string(R.string.action_not_now),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                rule.onNodeWithText(string(R.string.action_not_now)).performClick()
            }
            rule.runOnIdle { assertTrue(navigator.commands.value.isEmpty()) }
            // Same command path as onNewIntent, and the current top destination is not duplicated.
            navigator.follow(AppLinks.Uploads)
            navigator.open(NextGalleryRoute.Uploads)
            rule.waitForIdle()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText(string(R.string.uploads_title)).assertIsDisplayed()
            Espresso.pressBack()
            rule.onNodeWithTag("library_page:Photos").assertIsSelected()
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithTag("library_page:Photos").assertIsSelected()
            rule.runOnIdle { assertTrue(navigator.commands.value.isEmpty()) }
        } finally {
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
            graph.close()
        }
    }
}
