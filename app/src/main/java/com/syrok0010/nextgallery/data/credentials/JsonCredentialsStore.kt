package com.syrok0010.nextgallery.data.credentials

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

internal interface CredentialsTextStore {
    fun loadText(): String?
    fun saveText(value: String)
    fun clear()
}

internal class JsonCredentialsStore(
    private val textStore: CredentialsTextStore,
    private val json: Json,
) : CredentialsStore {
    override fun load(): AccountCredentials? {
        val payload = textStore.loadText() ?: return null

        return try {
            json.decodeFromString<AccountCredentials>(payload).takeIf { it.isComplete() }
        } catch (_: SerializationException) {
            textStore.clear()
            null
        } catch (_: IllegalArgumentException) {
            textStore.clear()
            null
        }
    }

    override fun save(credentials: AccountCredentials) {
        require(credentials.isComplete()) { "Credentials must contain serverUrl, loginName and appPassword." }
        textStore.saveText(json.encodeToString(credentials))
    }

    override fun clear() {
        textStore.clear()
    }

    private fun AccountCredentials.isComplete(): Boolean {
        return serverUrl.isNotBlank() && loginName.isNotBlank() && appPassword.isNotBlank()
    }
}
