package com.biafra23.anchorvault.sync

import com.biafra23.anchorvault.model.Bookmark
import kotlinx.serialization.Serializable

/**
 * Result of a Bitwarden sync operation.
 */
sealed class SyncResult {
    data object Success : SyncResult()
    data class Error(val message: String) : SyncResult()
    data object NotConfigured : SyncResult()
}

/**
 * Bitwarden vault item representation for URL bookmarks stored as secure notes.
 */
@Serializable
data class BitwardenItem(
    val id: String,
    val name: String,
    val notes: String?,
    val url: String? = null,
    val isFavorite: Boolean = false,
    val type: Int = 1 // 1 = Login (with clickable URIs)
)

/**
 * Service interface for syncing bookmarks with Bitwarden.
 *
 * Bitwarden stores bookmarks as Secure Notes in a designated vault folder.
 * The JSON structure of a bookmark is serialized into the note's body.
 *
 * To use Bitwarden sync, configure [BitwardenCredentials] via [configure].
 */
interface BitwardenSyncService {
    /**
     * Configure the Bitwarden credentials for sync operations.
     */
    suspend fun configure(credentials: BitwardenCredentials)

    /**
     * Push local bookmarks to the Bitwarden vault.
     */
    suspend fun pushBookmarks(bookmarks: List<Bookmark>): SyncResult

    /**
     * Pull bookmarks from the Bitwarden vault.
     */
    suspend fun pullBookmarks(): Result<List<Bookmark>>

    /**
     * Perform a full two-way sync between local storage and Bitwarden vault.
     * Items are merged by [Bookmark.id]; the most recently updated version wins.
     */
    suspend fun syncAll(localBookmarks: List<Bookmark>): Result<List<Bookmark>>

    /**
     * Returns true if Bitwarden sync has been configured with valid credentials.
     */
    fun isConfigured(): Boolean

    /**
     * Validates credentials by authenticating and ensuring the sync folder exists
     * (creating it if necessary). Returns a descriptive error message on failure,
     * or null on success.
     */
    suspend fun validateCredentials(credentials: BitwardenCredentials): String?
}

/**
 * Credentials required to authenticate with the Bitwarden REST API
 * using email + master password (password grant with prelogin KDF).
 *
 * @param apiBaseUrl     The base URL of the Bitwarden API (defaults to official cloud).
 * @param identityUrl    The base URL of the Bitwarden Identity service.
 * @param email          Account email.
 * @param masterPassword Master password used to authenticate and derive vault encryption keys.
 * @param folderName     Name of the Bitwarden folder used to store AnchorVault bookmarks.
 */
@Serializable
data class BitwardenCredentials(
    val apiBaseUrl: String = "https://api.bitwarden.com",
    val identityUrl: String = "https://identity.bitwarden.com",
    val folderName: String = "AnchorVault",
    val masterPassword: String? = null,
    val email: String? = null
)
