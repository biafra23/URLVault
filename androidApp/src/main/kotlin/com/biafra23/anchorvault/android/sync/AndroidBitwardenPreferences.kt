package com.biafra23.anchorvault.android.sync

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.biafra23.anchorvault.sync.BitwardenCredentials
import com.biafra23.anchorvault.sync.SettingsFieldHistory
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

    fun saveAutoTagEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_TAG_ENABLED, enabled).apply()
    }

    fun loadAutoTagEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_TAG_ENABLED, false)
    }

    fun saveAiCoreEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_CORE_ENABLED, enabled).apply()
    }

    fun loadAiCoreEnabled(): Boolean {
        return prefs.getBoolean(KEY_AI_CORE_ENABLED, false)
    }

    fun saveFieldHistory(history: SettingsFieldHistory) {
        prefs.edit()
            .putString(KEY_FIELD_HISTORY, json.encodeToString(history))
            .apply()
    }

    fun loadFieldHistory(): SettingsFieldHistory {
        val raw = prefs.getString(KEY_FIELD_HISTORY, null) ?: return SettingsFieldHistory()
        return runCatching { json.decodeFromString<SettingsFieldHistory>(raw) }
            .getOrDefault(SettingsFieldHistory())
    }

    fun addToFieldHistory(credentials: BitwardenCredentials) {
        val existing = loadFieldHistory()
        val updated = SettingsFieldHistory(
            apiBaseUrls = (existing.apiBaseUrls + credentials.apiBaseUrl).filter { it.isNotBlank() }.distinct(),
            identityUrls = (existing.identityUrls + credentials.identityUrl).filter { it.isNotBlank() }.distinct(),
            folderNames = (existing.folderNames + credentials.folderName).filter { it.isNotBlank() }.distinct(),
            emails = (existing.emails + listOfNotNull(credentials.email)).filter { it.isNotBlank() }.distinct()
        )
        saveFieldHistory(updated)
    }

    companion object {
        private const val PREFS_NAME = "anchorvault_bitwarden_encrypted"
        private const val KEY_CREDENTIALS = "credentials"
        private const val KEY_AUTO_TAG_ENABLED = "auto_tag_enabled"
        private const val KEY_AI_CORE_ENABLED = "ai_core_enabled"
        private const val KEY_FIELD_HISTORY = "field_history"
    }
}
