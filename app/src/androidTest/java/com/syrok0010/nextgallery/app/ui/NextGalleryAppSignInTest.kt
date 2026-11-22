package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.di.ProductionGraph
import com.syrok0010.nextgallery.string
import org.junit.Rule
import org.junit.Test

class NextGalleryAppSignInTest {
    @get:Rule val rule = createComposeRule()

    @Test fun signInTransitionsFromLoginToAuthenticatedApp() {
        val account = AccountCredentials("http://127.0.0.1:9", "fixture", "password", "fixture")
        val graph = ProductionGraph(credentials = null)
        val mounted = mutableStateOf(true)
        try {
            rule.setContent {
                if (mounted.value) graph.Content { NextGalleryApp() }
            }
            val loginTitle = string(R.string.login_title)
            val photosTitle = string(R.string.library_photos)
            rule.onNodeWithText(loginTitle).assertIsDisplayed()

            rule.runOnUiThread { graph.koin.get<SessionStore>().signIn(account) }

            rule.waitUntil(10_000) {
                rule.onAllNodesWithText(photosTitle).fetchSemanticsNodes().isNotEmpty() &&
                    rule.onAllNodesWithText(loginTitle).fetchSemanticsNodes().isEmpty()
            }
            rule.onNodeWithText(photosTitle).assertIsDisplayed()
        } finally {
            rule.runOnIdle { mounted.value = false }
            rule.waitForIdle()
            graph.close()
        }
    }
}
