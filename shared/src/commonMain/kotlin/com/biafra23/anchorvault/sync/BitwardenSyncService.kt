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
 * Authentication method for the Bitwarden API.
 */
@Serializable
enum class AuthMethod {
    /** OAuth 2.0 client credentials (API key from Bitwarden settings). */
    API_KEY,
    /** Email + master password (password grant with prelogin KDF). */
    PASSWORD
}

/**
 * Credentials required to authenticate with the Bitwarden REST API.
 *
 * @param authMethod     How to authenticate: [AuthMethod.API_KEY] or [AuthMethod.PASSWORD].
 * @param apiBaseUrl     The base URL of the Bitwarden API (defaults to official cloud).
 * @param identityUrl    The base URL of the Bitwarden Identity service.
 * @param clientId       OAuth 2.0 client ID (required for [AuthMethod.API_KEY]).
 * @param clientSecret   OAuth 2.0 client secret (required for [AuthMethod.API_KEY]).
 * @param email          Account email (required for [AuthMethod.PASSWORD], optional for API_KEY encryption).
 * @param masterPassword Master password (required for [AuthMethod.PASSWORD], optional for API_KEY encryption).
 * @param folderName     Name of the Bitwarden folder used to store AnchorVault bookmarks.
 */
@Serializable
data class BitwardenCredentials(
    val authMethod: AuthMethod = AuthMethod.API_KEY,
    val apiBaseUrl: String = "https://api.bitwarden.com",
    val identityUrl: String = "https://identity.bitwarden.com",
    val clientId: String = "",
    val clientSecret: String = "",
    val folderName: String = "AnchorVault",
    val masterPassword: String? = null,
    val email: String? = null
)
