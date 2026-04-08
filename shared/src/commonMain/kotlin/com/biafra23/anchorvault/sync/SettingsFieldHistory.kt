package com.biafra23.anchorvault.sync

import kotlinx.serialization.Serializable

@Serializable
data class SettingsFieldHistory(
    val apiBaseUrls: List<String> = emptyList(),
    val identityUrls: List<String> = emptyList(),
    val folderNames: List<String> = emptyList(),
    val emails: List<String> = emptyList()
    // masterPassword intentionally excluded
)
