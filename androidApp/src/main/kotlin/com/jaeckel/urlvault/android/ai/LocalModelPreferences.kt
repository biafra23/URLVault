package com.jaeckel.urlvault.android.ai

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.jaeckel.urlvault.ai.ModelCatalogEntry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Persists user-side model metadata: which catalog entries the user added,
 * which downloaded models are "active" for routine bookmark AI, and an
 * optional Hugging Face token for gated repos.
 */
class LocalModelPreferences(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun loadCustomEntries(): List<ModelCatalogEntry> {
        val raw = prefs.getString(KEY_CUSTOM_ENTRIES, null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<ModelCatalogEntry>>(raw) }.getOrDefault(emptyList())
    }

    fun saveCustomEntries(entries: List<ModelCatalogEntry>) {
        prefs.edit().putString(KEY_CUSTOM_ENTRIES, json.encodeToString(entries)).apply()
    }

    fun loadActiveIds(): Set<String> {
        return prefs.getStringSet(KEY_ACTIVE_IDS, emptySet()) ?: emptySet()
    }

    fun saveActiveIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_ACTIVE_IDS, ids).apply()
    }

    fun loadHfToken(): String? = prefs.getString(KEY_HF_TOKEN, null)

    fun saveHfToken(token: String?) {
        prefs.edit().apply {
            if (token.isNullOrBlank()) remove(KEY_HF_TOKEN) else putString(KEY_HF_TOKEN, token)
        }.apply()
    }

    companion object {
        private const val PREFS_NAME = "urlvault_local_models_encrypted"
        private const val KEY_CUSTOM_ENTRIES = "custom_entries"
        private const val KEY_ACTIVE_IDS = "active_ids"
        private const val KEY_HF_TOKEN = "hf_token"
    }
}
