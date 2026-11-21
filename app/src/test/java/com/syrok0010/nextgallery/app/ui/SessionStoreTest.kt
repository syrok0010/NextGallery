package com.syrok0010.nextgallery.app.ui

import com.syrok0010.nextgallery.core.session.AccountCredentials
import com.syrok0010.nextgallery.core.session.CredentialsStore
import com.syrok0010.nextgallery.core.session.SessionStore
import com.syrok0010.nextgallery.core.session.SessionUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStoreTest {
    @Test
    fun `store starts signed out when credentials are missing`() {
        val store = SessionStore(FakeCredentialsStore(credentials = null))

        assertEquals(SessionUiState.SignedOut, store.session.value)
    }

    @Test
    fun `store starts signed in when credentials exist`() {
        val credentials = credentials()
        val store = SessionStore(FakeCredentialsStore(credentials))

        assertEquals(SessionUiState.SignedIn(credentials), store.session.value)
    }

    @Test
    fun `sign in updates session state`() {
        val credentials = credentials()
        val store = SessionStore(FakeCredentialsStore(credentials = null))

        store.signIn(credentials)
        assertEquals(SessionUiState.SignedIn(credentials), store.session.value)
    }

    @Test fun `failed graph preparation cannot publish signed in`() {
        val store = SessionStore(FakeCredentialsStore(null)) { error("graph unavailable") }
        org.junit.Assert.assertThrows(
            IllegalStateException::class.java,
        ) { store.signIn(credentials()) }
        assertEquals(SessionUiState.SignedOut, store.session.value)
    }

    private fun credentials(): AccountCredentials =
        AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
            userId = "user",
        )
}

private class FakeCredentialsStore(private val credentials: AccountCredentials?) :
    CredentialsStore {
    override fun load(): AccountCredentials? = credentials

    override fun save(credentials: AccountCredentials) = Unit
}
