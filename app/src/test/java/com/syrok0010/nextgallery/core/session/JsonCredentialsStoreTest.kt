package com.syrok0010.nextgallery.core.session

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonCredentialsStoreTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun `save load and clear credentials`() {
        val textStore = FakeCredentialsTextStore()
        val store = JsonCredentialsStore(textStore, json)
        val credentials = AccountCredentials(
            serverUrl = "https://cloud.example.com",
            loginName = "alice",
            appPassword = "app-password",
            userId = "alice",
        )

        store.save(credentials)

        assertEquals(credentials, store.load())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `old credentials receive one durable owner and a new login has a different owner`() {
        val text = FakeCredentialsTextStore("""{"serverUrl":"https://cloud.example.com","loginName":"alice","appPassword":"fixture","userId":"alice"}""")
        val store = JsonCredentialsStore(text, json)
        val restored = store.load()!!
        assertEquals(restored.ownerToken, store.load()!!.ownerToken)
        store.clear()
        val newLogin = AccountCredentials(restored.serverUrl, restored.loginName, restored.appPassword, restored.userId)
        org.junit.Assert.assertNotEquals(restored.ownerToken, newLogin.ownerToken)
        store.save(newLogin)
        assertEquals(newLogin.ownerToken, store.load()!!.ownerToken)
    }

    @Test
    fun `load clears malformed payload`() {
        val textStore = FakeCredentialsTextStore(initialValue = "not-json")
        val store = JsonCredentialsStore(textStore, json)

        assertNull(store.load())
        assertNull(textStore.loadText())
    }

    @Test
    fun `session saved without uid requires a new login`() {
        val textStore = FakeCredentialsTextStore(
            initialValue = """{"serverUrl":"https://cloud.example.com","loginName":"alice","appPassword":"secret"}""",
        )
        val store = JsonCredentialsStore(textStore, json)

        assertNull(store.load())
        assertNull(textStore.loadText())
    }
}

private class FakeCredentialsTextStore(initialValue: String? = null) : CredentialsTextStore {
    private var value = initialValue

    override fun loadText(): String? = value

    override fun saveText(value: String) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}
