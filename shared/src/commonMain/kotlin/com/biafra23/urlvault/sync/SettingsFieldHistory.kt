package com.biafra23.urlvault.sync

import kotlinx.serialization.Serializable

/**
 * Persists history of fields entered in the Settings screen to provide autocomplete suggestions.
 * Values are stored normalized (e.g. server base URLs without /api suffix).
 */
@Serializable
data class SettingsFieldHistory(
    val serverUrls: List<String> = emptyList(),
    val folderNames: List<String> = emptyList(),
    val emails: List<String> = emptyList()
    // masterPassword intentionally excluded for security
)
