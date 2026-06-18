package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    @Test
    fun `login update applies only to signed out state`() {
        val state = MainUiState()

        val updated = state.updateLogin {
            it.copy(serverUrlInput = "https://cloud.example.com")
        }

        assertEquals("https://cloud.example.com", updated.loginState?.serverUrlInput)
    }

    @Test
    fun `login update does not alter signed in state`() {
        val state = MainUiState(
            session = SessionUiState.SignedIn(credentials = credentials()),
        )

        val updated = state.updateLogin {
            it.copy(serverUrlInput = "https://cloud.example.com")
        }

        assertSame(state, updated)
    }

    @Test
    fun `timeline update applies only to signed in state`() {
        val state = MainUiState(
            session = SessionUiState.SignedIn(credentials = credentials()),
        )

        val updated = state.updateTimeline {
            it.copy(loadingDayIds = setOf(19870))
        }

        assertEquals(setOf(19870), updated.signedIn?.timeline?.loadingDayIds)
    }

    @Test
    fun `timeline update does not alter signed out state`() {
        val state = MainUiState()

        val updated = state.updateTimeline {
            it.copy(loadingDayIds = setOf(19870))
        }

        assertSame(state, updated)
    }

    @Test
    fun `login polling belongs to login state`() {
        val signedOut = MainUiState(
            session = SessionUiState.SignedOut(
                login = LoginUiState(isPolling = true),
            ),
        )
        val signedIn = MainUiState(
            session = SessionUiState.SignedIn(credentials = credentials()),
        )

        assertTrue(signedOut.isLoginPolling)
        assertFalse(signedIn.isLoginPolling)
    }

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }
}
