package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
import com.syrok0010.nextgallery.string
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LogoutMenuTest {
    @get:Rule val rule = createComposeRule()

    @Test fun openMenuDisablesLogoutWhenOperationStartsAndEnablesItWhenFinished() {
        val running = mutableStateOf(false)
        var logouts = 0
        rule.setContent {
            NextGalleryTheme {
                LibraryHeader(
                    destination = TopLevelDestination.Photos,
                    onRefresh = {},
                    onLogout = { logouts++ },
                    logoutEnabled = !running.value,
                )
            }
        }
        rule.onNodeWithContentDescription(string(R.string.library_menu)).performClick()
        rule.onNodeWithText(string(R.string.action_logout)).assertIsEnabled()
        rule.runOnIdle { running.value = true }
        rule.onNodeWithText(string(R.string.action_logout)).assertIsNotEnabled()
        rule
            .onNodeWithText(
                string(R.string.logout_wait_for_operations),
                substring = true,
            ).assertIsDisplayed()
        rule.onNodeWithText(string(R.string.action_logout)).performClick()
        rule.runOnIdle {
            assertEquals(0, logouts)
            running.value = false
        }
        rule.onNodeWithText(string(R.string.action_logout)).assertIsEnabled()
        rule
            .onNodeWithText(
                string(R.string.logout_wait_for_operations),
                substring = true,
            ).assertDoesNotExist()
        rule.onNodeWithText(string(R.string.action_logout)).performClick()
        rule.runOnIdle { assertEquals(1, logouts) }
    }
}
