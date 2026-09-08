package com.syrok0010.nextgallery.data.credentials

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

class KeystoreCredentialsStore(
    context: Context,
    json: Json,
) : CredentialsStore {
    private val encryptedStore = JsonCredentialsStore(
        textStore = KeystoreEncryptedTextStore(
            preferences = context.applicationContext.getSharedPreferences(
                PREFERENCES_NAME,
                Context.MODE_PRIVATE,
            ),
            keyAlias = KEY_ALIAS,
        ),
        json = json,
    )

    override fun load(): AccountCredentials? = encryptedStore.load()

    override fun save(credentials: AccountCredentials) = encryptedStore.save(credentials)

    override fun clear() = encryptedStore.clear()

    private companion object {
        const val KEY_ALIAS = "nextgallery.credentials.v1"
        const val PREFERENCES_NAME = "nextgallery.credentials"
    }
}

private class KeystoreEncryptedTextStore(
    private val preferences: SharedPreferences,
    private val keyAlias: String,
) : CredentialsTextStore {
    override fun loadText(): String? {
        val iv = preferences.getString(KEY_IV, null)
        val ciphertext = preferences.getString(KEY_CIPHERTEXT, null)

        if (iv == null || ciphertext == null) {
            return null
        }

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, Base64.decode(iv, Base64.NO_WRAP)),
            )
            val plaintext = cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))
            String(plaintext, StandardCharsets.UTF_8)
        }.getOrNull()
    }

    override fun saveText(value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))

        preferences.edit {
            putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            putString(KEY_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
        }
    }

    override fun clear() {
        preferences.edit { clear() }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existingKey = keyStore.getKey(keyAlias, null) as? SecretKey
        if (existingKey != null) {
            return existingKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return keyGenerator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_IV = "iv"
        const val KEY_CIPHERTEXT = "ciphertext"
    }
}
