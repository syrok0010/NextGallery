package com.syrok0010.nextgallery.feature.auth

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.AppMessageUiState
import com.syrok0010.nextgallery.core.ui.uiText
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LoginCompatibilityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun blockedLoginOffersRetryAndExitWithoutStartingAnotherLoginFlow() {
        var retries = 0
        var exits = 0
        compose.setContent {
            MaterialTheme {
                LoginPanel(
                    state = LoginUiState(attempt = LoginAttempt.MemoriesBlocked),
                    message = AppMessageUiState(
                        error = uiText(R.string.error_memories_unavailable),
                    ),
                    isBusy = false,
                    onServerUrlChange = { error("unexpected server change") },
                    onStartLogin = { error("unexpected login flow") },
                    onLoginBrowserOpened = {},
                    onLoginBrowserOpenFailed = {},
                    onCancelLogin = { exits++ },
                    onRetryMemoriesCheck = { retries++ },
                )
            }
        }
        compose
            .onNodeWithText(
                context.getString(R.string.error_memories_unavailable),
            ).assertIsDisplayed()
        compose
            .onNodeWithText(
                context.getString(R.string.action_retry_memories_check),
            ).performClick()
        compose.onNodeWithText(context.getString(R.string.action_logout)).performClick()
        compose.onNodeWithText(context.getString(R.string.action_start_login)).assertDoesNotExist()
        assertEquals(1, retries)
        assertEquals(1, exits)
    }

    @Test fun checkingDisablesRetryButAllowsExit() {
        var exits = 0
        compose.setContent {
            MaterialTheme {
                LoginPanel(
                    state = LoginUiState(attempt = LoginAttempt.CheckingMemories),
                    message = AppMessageUiState(status = uiText(R.string.status_checking_memories)),
                    isBusy = true,
                    onServerUrlChange = {},
                    onStartLogin = {},
                    onLoginBrowserOpened = {},
                    onLoginBrowserOpenFailed = {},
                    onCancelLogin = { exits++ },
                    onRetryMemoriesCheck = { error("unexpected retry") },
                )
            }
        }
        compose
            .onNodeWithText(
                context.getString(R.string.action_retry_memories_check),
            ).assertIsNotEnabled()
        compose.onNodeWithText(context.getString(R.string.action_logout)).performClick()
        assertEquals(1, exits)
    }
}
