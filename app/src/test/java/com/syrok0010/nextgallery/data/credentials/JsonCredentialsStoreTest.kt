package com.syrok0010.nextgallery.data.credentials

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
        )

        store.save(credentials)

        assertEquals(credentials, store.load())

        store.clear()

        assertNull(store.load())
    }

    @Test
    fun `load clears malformed payload`() {
        val textStore = FakeCredentialsTextStore(initialValue = "not-json")
        val store = JsonCredentialsStore(textStore, json)

        assertNull(store.load())
        assertNull(textStore.loadText())
    }
}

private class FakeCredentialsTextStore(
    initialValue: String? = null,
) : CredentialsTextStore {
    private var value = initialValue

    override fun loadText(): String? = value

    override fun saveText(value: String) {
        this.value = value
    }

    override fun clear() {
        value = null
    }
}
