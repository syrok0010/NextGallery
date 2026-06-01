package com.syrok0010.nextgallery.data.credentials

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesCredentialsStore(
    context: Context,
) : CredentialsStore {
    private val preferences = context.getSharedPreferences("nextgallery.credentials", Context.MODE_PRIVATE)

    override fun load(): AccountCredentials? {
        val serverUrl = preferences.getString(KEY_SERVER_URL, null)
        val loginName = preferences.getString(KEY_LOGIN_NAME, null)
        val appPassword = preferences.getString(KEY_APP_PASSWORD, null)

        if (serverUrl.isNullOrBlank() || loginName.isNullOrBlank() || appPassword.isNullOrBlank()) {
            return null
        }

        return AccountCredentials(
            serverUrl = serverUrl,
            loginName = loginName,
            appPassword = appPassword,
        )
    }

    override fun save(credentials: AccountCredentials) {
        // MVP-only implementation. Replace with Android Keystore-backed storage before real use.
        preferences.edit {
            putString(KEY_SERVER_URL, credentials.serverUrl)
                .putString(KEY_LOGIN_NAME, credentials.loginName)
                .putString(KEY_APP_PASSWORD, credentials.appPassword)
        }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    private companion object {
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_LOGIN_NAME = "loginName"
        const val KEY_APP_PASSWORD = "appPassword"
    }
}
