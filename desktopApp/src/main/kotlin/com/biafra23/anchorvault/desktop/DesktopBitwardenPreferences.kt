package com.biafra23.anchorvault.desktop

import com.biafra23.anchorvault.sync.BitwardenCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File-based persistence for Bitwarden credentials on Desktop.
 * Stores credentials as JSON in `~/.anchorvault/credentials.json`.
 *
 * Note: This file is not encrypted. Desktop OS-level file permissions
 * (user-only read/write) provide the primary access control.
 */
class DesktopBitwardenPreferences {

    private val credentialsFile: java.io.File = run {
        val home = System.getProperty("user.home")
        val dir = java.io.File("$home/.anchorvault")
        dir.mkdirs()
        java.io.File(dir, "credentials.json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun saveCredentials(credentials: BitwardenCredentials) {
        credentialsFile.writeText(json.encodeToString(credentials))
    }

    fun loadCredentials(): BitwardenCredentials? {
        if (!credentialsFile.exists()) return null
        return runCatching {
            json.decodeFromString<BitwardenCredentials>(credentialsFile.readText())
        }.getOrNull()
    }

    fun clearCredentials() {
        if (credentialsFile.exists()) credentialsFile.delete()
    }
}
