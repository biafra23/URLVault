package com.biafra23.anchorvault.sync

import com.biafra23.anchorvault.model.Bookmark
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Ktor-based implementation of [BitwardenSyncService] that communicates with the
 * Bitwarden REST API to push/pull bookmarks stored as Secure Notes.
 */
class KtorBitwardenSyncService(private val httpClient: HttpClient) : BitwardenSyncService {

    private var credentials: BitwardenCredentials? = null
    private var accessToken: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun configure(credentials: BitwardenCredentials) {
        this.credentials = credentials
        this.accessToken = null
    }

    override fun isConfigured(): Boolean = credentials != null

    override suspend fun pushBookmarks(bookmarks: List<Bookmark>): SyncResult {
        val creds = credentials ?: return SyncResult.NotConfigured
        return try {
            val token = getAccessToken(creds) ?: return SyncResult.Error("Authentication failed")
            val folderId = getOrCreateFolder(creds, token, creds.folderName)
            bookmarks.forEach { bookmark ->
                val itemBody = BitwardenItem(
                    id = bookmark.id,
                    name = bookmark.title.ifBlank { bookmark.url },
                    notes = json.encodeToString(Bookmark.serializer(), bookmark),
                    type = 2
                )
                upsertVaultItem(creds, token, folderId, itemBody)
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
        return pullBookmarks().map { remoteBookmarks ->
            val merged = mergeBookmarks(localBookmarks, remoteBookmarks)
            pushBookmarks(merged)
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

    // region Private Bitwarden API helpers

    @Serializable
    private data class TokenResponse(val access_token: String)

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
        if (accessToken != null) return accessToken
        return try {
            val response: TokenResponse = httpClient.post("${creds.identityUrl}/connect/token") {
                contentType(ContentType.Application.FormUrlEncoded)
                setBody("grant_type=client_credentials&scope=api&client_id=${creds.clientId}&client_secret=${creds.clientSecret}")
            }.body()
            accessToken = response.access_token
            accessToken
        } catch (_: Exception) {
            null
        }
    }

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
                    setBody(mapOf("name" to folderName))
                }.body()
                created.id
            }
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchVaultItems(
        creds: BitwardenCredentials,
        token: String,
        folderName: String
    ): List<VaultItemResponse> {
        return try {
            val folderId = getOrCreateFolder(creds, token, folderName)
            val items: VaultListResponse = httpClient.get("${creds.apiBaseUrl}/ciphers") {
                bearerAuth(token)
            }.body()
            items.data.filter { it.folderId == folderId && it.type == 2 }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun upsertVaultItem(
        creds: BitwardenCredentials,
        token: String,
        folderId: String?,
        item: BitwardenItem
    ) {
        try {
            val existingItems: VaultListResponse = httpClient.get("${creds.apiBaseUrl}/ciphers") {
                bearerAuth(token)
            }.body()
            val existingId = existingItems.data
                .firstOrNull { it.folderId == folderId && it.name == item.name }?.id

            val body = buildMap {
                put("type", item.type)
                put("name", item.name)
                put("notes", item.notes)
                if (folderId != null) put("folderId", folderId)
                put("secureNote", mapOf("type" to 0))
            }

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
        install(Logging) {
            level = LogLevel.INFO
        }
    }
    return KtorBitwardenSyncService(client)
}
