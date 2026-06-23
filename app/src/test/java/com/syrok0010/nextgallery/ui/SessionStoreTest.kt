package com.syrok0010.nextgallery.ui

import com.syrok0010.nextgallery.data.credentials.AccountCredentials
import com.syrok0010.nextgallery.data.credentials.CredentialsStore
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
    fun `sign in and sign out update session state`() {
        val credentials = credentials()
        val store = SessionStore(FakeCredentialsStore(credentials = null))

        store.signIn(credentials)
        assertEquals(SessionUiState.SignedIn(credentials), store.session.value)

        store.signOut()
        assertEquals(SessionUiState.SignedOut, store.session.value)
    }

    private fun credentials(): AccountCredentials {
        return AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "user",
            appPassword = "secret",
        )
    }
}

private class FakeCredentialsStore(
    private val credentials: AccountCredentials?,
) : CredentialsStore {
    override fun load(): AccountCredentials? = credentials

    override fun save(credentials: AccountCredentials) = Unit

    override fun clear() = Unit
}
