package com.biafra23.anchorvault.android.sync

import android.content.Context
import com.biafra23.anchorvault.sync.BitwardenCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists Bitwarden credentials in encrypted SharedPreferences.
 *
 * Note: credentials are stored in private SharedPreferences (MODE_PRIVATE).
 * For production use, consider using EncryptedSharedPreferences from Jetpack Security.
 */
class AndroidBitwardenPreferences(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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
        private const val PREFS_NAME = "anchorvault_bitwarden"
        private const val KEY_CREDENTIALS = "credentials"
    }
}
