package com.syrok0010.nextgallery.app.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.syrok0010.nextgallery.core.ui.theme.NextGalleryTheme
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
        rule.onNodeWithContentDescription("Меню медиатеки").performClick()
        rule.onNodeWithText("Выйти").assertIsEnabled()
        rule.runOnIdle { running.value = true }
        rule.onNodeWithText("Выйти").assertIsNotEnabled()
        rule.onNodeWithText("Дождитесь завершения операций", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Выйти").performClick()
        rule.runOnIdle {
            assertEquals(0, logouts)
            running.value = false
        }
        rule.onNodeWithText("Выйти").assertIsEnabled()
        rule.onNodeWithText("Дождитесь завершения операций", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Выйти").performClick()
        rule.runOnIdle { assertEquals(1, logouts) }
    }
}
