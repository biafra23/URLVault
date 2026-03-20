package com.biafra23.anchorvault.sync

import com.biafra23.anchorvault.model.Bookmark
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * Ktor-based implementation of [BitwardenSyncService] that communicates with the
 * Bitwarden REST API to push/pull bookmarks stored as Secure Notes.
 */
class KtorBitwardenSyncService(private val httpClient: HttpClient) : BitwardenSyncService {

    private val authMutex = Mutex()
    private var credentials: BitwardenCredentials? = null
    private var accessToken: String? = null
    private var tokenExpiresAtMillis: Long = 0L

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun configure(credentials: BitwardenCredentials) {
        authMutex.withLock {
            this.credentials = credentials
            this.accessToken = null
            this.tokenExpiresAtMillis = 0L
        }
    }

    override fun isConfigured(): Boolean = credentials != null

    override suspend fun pushBookmarks(bookmarks: List<Bookmark>): SyncResult {
        val creds = credentials ?: return SyncResult.NotConfigured
        return try {
            val token = getAccessToken(creds) ?: return SyncResult.Error("Authentication failed")
            val folderId = getOrCreateFolder(creds, token, creds.folderName)

            // Fetch all ciphers once to avoid N+1 API calls
            val allCiphers = fetchAllCiphers(creds, token)

            bookmarks.forEach { bookmark ->
                val itemBody = BitwardenItem(
                    id = bookmark.id,
                    name = bookmark.title.ifBlank { bookmark.url },
                    notes = json.encodeToString(Bookmark.serializer(), bookmark),
                    type = 2
                )
                upsertVaultItem(creds, token, folderId, itemBody, allCiphers)
            }
            SyncResult.Success
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Unknown error during push")
        }
    }

    override suspend fun pullBookmarks(): Result<List<Bookmark>> {
        val creds = credentials ?: return Result.failure(IllegalStateException("Not configured"))
        return try {
            val token = getAccessToken(creds)
                ?: return Result.failure(IllegalStateException("Authentication failed"))
            val items = fetchVaultItems(creds, token, creds.folderName)
            val bookmarks = items.mapNotNull { item ->
                item.notes?.let { notes ->
                    runCatching { json.decodeFromString(Bookmark.serializer(), notes) }.getOrNull()
                }
            }
            Result.success(bookmarks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncAll(localBookmarks: List<Bookmark>): Result<List<Bookmark>> {
        return pullBookmarks().mapCatching { remoteBookmarks ->
            val merged = mergeBookmarks(localBookmarks, remoteBookmarks)
            val pushResult = pushBookmarks(merged)
            if (pushResult is SyncResult.Error) {
                throw IllegalStateException("Push failed: ${pushResult.message}")
            }
            merged
        }
    }

    /**
     * Merges local and remote bookmark lists. The most recently updated bookmark wins
     * on ID conflicts.
     */
    private fun mergeBookmarks(local: List<Bookmark>, remote: List<Bookmark>): List<Bookmark> {
        val map = mutableMapOf<String, Bookmark>()
        local.forEach { map[it.id] = it }
        remote.forEach { remoteItem ->
            val existing = map[remoteItem.id]
            if (existing == null || remoteItem.updatedAt > existing.updatedAt) {
                map[remoteItem.id] = remoteItem
            }
        }
        return map.values.toList()
    }

    fun close() {
        httpClient.close()
    }

    // region Private Bitwarden API helpers

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Long = 3600
    )

    @Serializable
    private data class FolderResponse(val id: String, val name: String)

    @Serializable
    private data class FolderListResponse(val data: List<FolderResponse>)

    @Serializable
    private data class VaultItemResponse(
        val id: String,
        val name: String,
        val notes: String? = null,
        val folderId: String? = null,
        val type: Int = 2
    )

    @Serializable
    private data class VaultListResponse(val data: List<VaultItemResponse>)

    private suspend fun getAccessToken(creds: BitwardenCredentials): String? {
        authMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (accessToken != null && now < tokenExpiresAtMillis) return accessToken
            // Token expired or missing — re-authenticate
            accessToken = null
        }
        return try {
            val response: TokenResponse = httpClient.submitForm(
                url = "${creds.identityUrl}/connect/token",
                formParameters = Parameters.build {
                    append("grant_type", "client_credentials")
                    append("scope", "api")
                    append("client_id", creds.clientId)
                    append("client_secret", creds.clientSecret)
                }
            ).body()
            authMutex.withLock {
                val now = Clock.System.now().toEpochMilliseconds()
                accessToken = response.access_token
                // Expire 60 seconds early to avoid edge-case failures
                tokenExpiresAtMillis = now + (response.expires_in - 60) * 1000
                accessToken
            }
        } catch (_: Exception) {
            null
        }
    }

    @Serializable
    private data class FolderRequest(val name: String)

    private suspend fun getOrCreateFolder(
        creds: BitwardenCredentials,
        token: String,
        folderName: String
    ): String? {
        return try {
            val folders: FolderListResponse = httpClient.get("${creds.apiBaseUrl}/folders") {
                bearerAuth(token)
            }.body()
            val existing = folders.data.firstOrNull { it.name == folderName }
            if (existing != null) {
                existing.id
            } else {
                val created: FolderResponse = httpClient.post("${creds.apiBaseUrl}/folders") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(FolderRequest(folderName))
                }.body()
                created.id
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchAllCiphers(
        creds: BitwardenCredentials,
        token: String
    ): List<VaultItemResponse> {
        return try {
            val items: VaultListResponse = httpClient.get("${creds.apiBaseUrl}/ciphers") {
                bearerAuth(token)
            }.body()
            items.data
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchVaultItems(
        creds: BitwardenCredentials,
        token: String,
        folderName: String
    ): List<VaultItemResponse> {
        return try {
            val folderId = getOrCreateFolder(creds, token, folderName)
            val items = fetchAllCiphers(creds, token)
            items.filter { it.folderId == folderId && it.type == 2 }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Serializable
    private data class SecureNoteType(val type: Int = 0)

    @Serializable
    private data class CipherRequest(
        val type: Int,
        val name: String,
        val notes: String?,
        val folderId: String? = null,
        val secureNote: SecureNoteType = SecureNoteType()
    )

    /**
     * Upserts a vault item, matching by bookmark ID embedded in the notes JSON
     * rather than by display name (which can change on rename).
     */
    private suspend fun upsertVaultItem(
        creds: BitwardenCredentials,
        token: String,
        folderId: String?,
        item: BitwardenItem,
        allCiphers: List<VaultItemResponse>
    ) {
        try {
            // Match by bookmark ID in the serialized notes, not by display name
            val existingId = allCiphers
                .filter { it.folderId == folderId && it.type == 2 }
                .firstOrNull { cipher ->
                    cipher.notes?.let { notes ->
                        runCatching {
                            json.decodeFromString(Bookmark.serializer(), notes).id
                        }.getOrNull() == item.id
                    } ?: false
                }?.id

            val body = CipherRequest(
                type = item.type,
                name = item.name,
                notes = item.notes,
                folderId = folderId
            )

            if (existingId != null) {
                httpClient.put("${creds.apiBaseUrl}/ciphers/$existingId") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            } else {
                httpClient.post("${creds.apiBaseUrl}/ciphers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (_: Exception) {
            // Best-effort sync; individual item failures do not abort the full sync
        }
    }

    // endregion
}

/**
 * Factory function to create a [KtorBitwardenSyncService] with a default HTTP client.
 */
fun createBitwardenSyncService(): KtorBitwardenSyncService {
    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    return KtorBitwardenSyncService(client)
}
