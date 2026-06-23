package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import org.junit.Assert.assertEquals
import org.junit.Test

class NextGalleryAppRouteTest {
    @Test
    fun `signed out session maps to login route`() {
        assertEquals(
            NextGalleryRoute.Login,
            SessionUiState.SignedOut.rootRoute(),
        )
    }

    @Test
    fun `signed in session maps to authenticated route`() {
        assertEquals(
            NextGalleryRoute.Authenticated,
            SessionUiState.SignedIn(credentials = credentials()).rootRoute(),
        )
    }

    @Test
    fun `signed out session resets authenticated back stack to login`() {
        val backStack = listOf(NextGalleryRoute.Authenticated)

        assertEquals(
            listOf(NextGalleryRoute.Login),
            syncedBackStack(backStack, SessionUiState.SignedOut),
        )
    }

    @Test
    fun `signed in session resets login back stack to authenticated`() {
        val backStack = listOf(NextGalleryRoute.Login)

        assertEquals(
            listOf(NextGalleryRoute.Authenticated),
            syncedBackStack(backStack, SessionUiState.SignedIn(credentials = credentials())),
        )
    }

    @Test
    fun `matching root route keeps existing back stack`() {
        val backStack = listOf(NextGalleryRoute.Authenticated)

        assertEquals(
            backStack,
            syncedBackStack(backStack, SessionUiState.SignedIn(credentials = credentials())),
        )
    }

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }
}
