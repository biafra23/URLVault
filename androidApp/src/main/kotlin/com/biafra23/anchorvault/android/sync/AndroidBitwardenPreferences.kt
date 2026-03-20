package com.biafra23.anchorvault.android.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.biafra23.anchorvault.sync.BitwardenCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists Bitwarden credentials in EncryptedSharedPreferences backed by Android Keystore.
 */
class AndroidBitwardenPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun saveCredentials(credentials: BitwardenCredentials) {
        prefs.edit()
            .putString(KEY_CREDENTIALS, json.encodeToString(credentials))
            .apply()
    }

    fun loadCredentials(): BitwardenCredentials? {
        val raw = prefs.getString(KEY_CREDENTIALS, null) ?: return null
        return runCatching { json.decodeFromString<BitwardenCredentials>(raw) }.getOrNull()
    }

    fun clearCredentials() {
        prefs.edit().remove(KEY_CREDENTIALS).apply()
    }

    companion object {
        private const val PREFS_NAME = "anchorvault_bitwarden_encrypted"
        private const val KEY_CREDENTIALS = "credentials"
    }
}
