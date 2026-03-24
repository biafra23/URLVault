package com.biafra23.anchorvault.sync

import com.biafra23.anchorvault.crypto.BitwardenEncryption
import com.biafra23.anchorvault.crypto.CryptoProvider
import com.biafra23.anchorvault.crypto.base64Encode
import com.biafra23.anchorvault.model.Bookmark
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.statement.HttpResponse
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.isSuccess
import io.ktor.http.Parameters
import io.ktor.http.contentType
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

    // Vault encryption keys — populated when master password is provided
    private var vaultEncKey: ByteArray? = null
    private var vaultMacKey: ByteArray? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }

    companion object {
        private const val TAG = "[AnchorVault:Sync]"
        // Stable device identifier so Bitwarden recognises this app across sessions.
        // This is not a secret — it simply identifies "AnchorVault" as a registered device.
        private const val DEVICE_IDENTIFIER = "b3a1c9d4-7e2f-4a8b-9c0d-1e2f3a4b5c6d"

        private fun log(message: String) = println("$TAG $message")
    }

    override suspend fun configure(credentials: BitwardenCredentials) {
        authMutex.withLock {
            this.credentials = credentials
            this.accessToken = null
            this.tokenExpiresAtMillis = 0L
            this.vaultEncKey = null
            this.vaultMacKey = null
        }
    }

    /** Whether vault encryption is active (master password was provided and keys derived). */
    private val isEncryptionEnabled: Boolean
        get() = vaultEncKey != null && vaultMacKey != null

    /** Encrypt a string for the vault if encryption is enabled, otherwise return plaintext. */
    private fun encryptIfEnabled(plaintext: String): String {
        val encKey = vaultEncKey
        val macKey = vaultMacKey
        return if (encKey != null && macKey != null) BitwardenEncryption.encryptString(plaintext, encKey, macKey)
        else plaintext
    }

    /** Decrypt a vault string if encryption is enabled, otherwise return as-is. */
    private fun decryptIfEnabled(value: String): String {
        val encKey = vaultEncKey
        val macKey = vaultMacKey
        return if (encKey != null && macKey != null && value.startsWith("2."))
            BitwardenEncryption.decryptToString(value, encKey, macKey)
        else value
    }

    override fun isConfigured(): Boolean = credentials != null

    override suspend fun validateCredentials(credentials: BitwardenCredentials): String? {
        log("Validating credentials (identityUrl=${credentials.identityUrl}, apiUrl=${credentials.apiBaseUrl})")
        return try {
            val token = getAccessToken(credentials)
                ?: return if (credentials.authMethod == AuthMethod.PASSWORD)
                    "Authentication failed — check your email, master password, and server URL."
                else
                    "Authentication failed — check your Client ID, Client Secret, and Identity URL."
            getOrCreateFolder(credentials, token, credentials.folderName)
            log("Credential validation successful")
            null // success
        } catch (e: Exception) {
            log("ERROR Credential validation failed: ${e.message}\n${e.stackTraceToString()}")
            e.message ?: "Unknown error during credential validation"
        }
    }

    override suspend fun pushBookmarks(bookmarks: List<Bookmark>): SyncResult {
        val creds = credentials ?: return SyncResult.NotConfigured
        log("Pushing ${bookmarks.size} bookmarks")
        return try {
            val token = getAccessToken(creds) ?: return SyncResult.Error("Authentication failed")
            val folderId = getOrCreateFolder(creds, token, creds.folderName)
            log("Using folderId=$folderId for push")

            // Fetch all ciphers once to avoid N+1 API calls
            val allCiphers = fetchAllCiphers(creds, token)

            bookmarks.forEach { bookmark ->
                val itemBody = BitwardenItem(
                    id = bookmark.id,
                    name = bookmark.title.ifBlank { bookmark.url },
                    notes = json.encodeToString(Bookmark.serializer(), bookmark),
                    url = bookmark.url,
                    isFavorite = bookmark.isFavorite,
                    type = 1 // Login type — gives clickable URIs in the Bitwarden web UI
                )
                upsertVaultItem(creds, token, folderId, itemBody, allCiphers)
            }
            log("Push completed successfully")
            SyncResult.Success
        } catch (e: Exception) {
            log("ERROR Push failed: ${e.message}\n${e.stackTraceToString()}")
            SyncResult.Error(e.message ?: "Unknown error during push")
        }
    }

    override suspend fun pullBookmarks(): Result<List<Bookmark>> {
        val creds = credentials ?: return Result.failure(IllegalStateException("Not configured"))
        log("Pulling bookmarks")
        return try {
            val token = getAccessToken(creds)
                ?: return Result.failure(IllegalStateException("Authentication failed"))
            val items = fetchVaultItems(creds, token, creds.folderName)
            log("Found ${items.size} vault items in folder '${creds.folderName}'")
            val bookmarks = items.mapNotNull { item ->
                item.notes?.let { notes ->
                    runCatching {
                        val decryptedNotes = decryptIfEnabled(notes)
                        json.decodeFromString(Bookmark.serializer(), decryptedNotes)
                    }.getOrNull()
                }
            }
            log("Pulled ${bookmarks.size} bookmarks successfully")
            Result.success(bookmarks)
        } catch (e: Exception) {
            log("ERROR Pull failed: ${e.message}\n${e.stackTraceToString()}")
            Result.failure(e)
        }
    }

    override suspend fun syncAll(localBookmarks: List<Bookmark>): Result<List<Bookmark>> {
        log("Starting full sync (${localBookmarks.size} local bookmarks)")
        return pullBookmarks().mapCatching { remoteBookmarks ->
            log("Merging ${localBookmarks.size} local + ${remoteBookmarks.size} remote bookmarks")
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
    private data class PreloginResponse(
        val kdf: Int = 0,              // 0 = PBKDF2, 1 = Argon2id
        val kdfIterations: Int = 600000
    )

    @Serializable
    private data class TokenResponse(
        val access_token: String,
        val expires_in: Long = 3600,
        // Encryption-related fields
        val Key: String? = null,
        val Kdf: Int? = null,          // 0 = PBKDF2, 1 = Argon2id
        val KdfIterations: Int? = null
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

    /**
     * Calls the /accounts/prelogin endpoint to retrieve KDF parameters for the account.
     */
    private suspend fun prelogin(creds: BitwardenCredentials): PreloginResponse {
        val email = requireNotNull(creds.email) { "Email is required for password authentication" }
        val preloginUrl = "${creds.apiBaseUrl}/accounts/prelogin"
        log("Prelogin for $email at $preloginUrl")
        val response = httpClient.post(preloginUrl) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("email" to email))
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.body<String>() }.getOrDefault("")
            log("ERROR Prelogin failed (${response.status}): $body")
            throw IllegalStateException(
                "Prelogin failed (${response.status}) at $preloginUrl: $body"
            )
        }
        return response.body<PreloginResponse>().also {
            log("Prelogin OK — kdf=${it.kdf}, iterations=${it.kdfIterations}")
        }
    }

    /**
     * Derives the "master password hash" that Bitwarden accepts as the password credential.
     * masterPasswordHash = base64(PBKDF2(masterKey, masterPassword, 1 iteration, 32 bytes))
     *
     * PBKDF2 with 1 iteration and 32-byte output (= one SHA-256 block) reduces to:
     *   HMAC-SHA256(key=masterKey, data=salt || INT32BE(1))
     *
     * We compute this directly via HMAC to avoid Java's PBEKeySpec char-encoding
     * issue which corrupts raw byte keys with values > 127.
     */
    private fun hashMasterPassword(masterKey: ByteArray, masterPassword: String): String {
        val salt = masterPassword.encodeToByteArray()
        val blockIndex = byteArrayOf(0, 0, 0, 1) // INT32BE(1)
        val hash = CryptoProvider.hmacSha256(masterKey, salt + blockIndex)
        return base64Encode(hash)
    }

    private suspend fun getAccessToken(creds: BitwardenCredentials): String? {
        authMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            if (accessToken != null && now < tokenExpiresAtMillis) return accessToken
            // Token expired or missing — re-authenticate
            accessToken = null
        }
        val tokenUrl = "${creds.identityUrl}/connect/token"
        log("Requesting token from $tokenUrl (authMethod=${creds.authMethod})")

        val httpResponse: HttpResponse = when (creds.authMethod) {
            AuthMethod.API_KEY -> httpClient.submitForm(
                url = tokenUrl,
                formParameters = Parameters.build {
                    append("grant_type", "client_credentials")
                    append("scope", "api")
                    append("client_id", creds.clientId)
                    append("client_secret", creds.clientSecret)
                    append("deviceType", "21")
                    append("deviceIdentifier", DEVICE_IDENTIFIER)
                    append("deviceName", "AnchorVault")
                }
            )
            AuthMethod.PASSWORD -> {
                val email = requireNotNull(creds.email) { "Email is required for password auth" }
                val password = requireNotNull(creds.masterPassword) { "Master password is required for password auth" }

                // Step 1: prelogin to get KDF params
                val preloginResp = prelogin(creds)
                require(preloginResp.kdf == 0) {
                    "Only PBKDF2 (kdf=0) is supported, got kdf=${preloginResp.kdf}"
                }

                // Step 2: derive master key and hash
                val masterKey = BitwardenEncryption.deriveMasterKey(
                    password, email, preloginResp.kdfIterations
                )
                val hashedPassword = hashMasterPassword(masterKey, password)

                // Step 3: token request with password grant
                httpClient.submitForm(
                    url = tokenUrl,
                    formParameters = Parameters.build {
                        append("grant_type", "password")
                        append("scope", "api offline_access")
                        append("client_id", "web")
                        append("username", email)
                        append("password", hashedPassword)
                        append("deviceType", "21")
                        append("deviceIdentifier", DEVICE_IDENTIFIER)
                        append("deviceName", "AnchorVault")
                    }
                )
            }
        }

        if (!httpResponse.status.isSuccess()) {
            val body = runCatching { httpResponse.body<String>() }.getOrDefault("")
            log("ERROR Token request failed (${httpResponse.status}): $body")
            throw IllegalStateException(
                "Token request failed (${httpResponse.status}) at $tokenUrl: $body"
            )
        }
        log("Token acquired successfully")
        val response: TokenResponse = httpResponse.body()

        // Derive vault encryption keys if master password is provided
        if (creds.masterPassword != null && creds.email != null && response.Key != null) {
            if (vaultEncKey == null) {
                try {
                    val kdfIterations = response.KdfIterations ?: 600000
                    val kdfType = response.Kdf ?: 0
                    require(kdfType == 0) { "Only PBKDF2 (Kdf=0) is supported, got Kdf=$kdfType" }

                    log("Deriving vault encryption keys (PBKDF2, $kdfIterations iterations)")
                    val masterKey = BitwardenEncryption.deriveMasterKey(
                        creds.masterPassword, creds.email, kdfIterations
                    )
                    val (stretchedEncKey, stretchedMacKey) = BitwardenEncryption.stretchMasterKey(masterKey)
                    val (vEncKey, vMacKey) = BitwardenEncryption.decryptEncryptionKey(
                        response.Key, stretchedEncKey, stretchedMacKey
                    )
                    vaultEncKey = vEncKey
                    vaultMacKey = vMacKey
                    log("Vault encryption keys derived successfully")
                } catch (e: Exception) {
                    log("ERROR Failed to derive vault keys: ${e.message}")
                    throw IllegalStateException(
                        "Failed to derive encryption keys — check your master password: ${e.message}"
                    )
                }
            }
        }

        return authMutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            accessToken = response.access_token
            // Expire 60 seconds early to avoid edge-case failures
            tokenExpiresAtMillis = now + (response.expires_in - 60) * 1000
            accessToken
        }
    }

    @Serializable
    private data class FolderRequest(val name: String)

    private suspend fun getOrCreateFolder(
        creds: BitwardenCredentials,
        token: String,
        folderName: String
    ): String {
        val foldersUrl = "${creds.apiBaseUrl}/folders"
        log("Listing folders from $foldersUrl")
        val foldersResponse = httpClient.get(foldersUrl) {
            bearerAuth(token)
        }
        if (!foldersResponse.status.isSuccess()) {
            val body = runCatching { foldersResponse.body<String>() }.getOrDefault("")
            log("ERROR Failed to list folders (${foldersResponse.status}): $body")
            throw IllegalStateException(
                "Failed to list folders (${foldersResponse.status}) at $foldersUrl: $body"
            )
        }
        val folders: FolderListResponse = foldersResponse.body()
        log("Found ${folders.data.size} folders")
        // When encryption is enabled, folder names are encrypted on the server.
        // Decrypt each to find a match by plaintext name.
        val existing = folders.data.firstOrNull { folder ->
            runCatching { decryptIfEnabled(folder.name) }.getOrDefault(folder.name) == folderName
        }
        if (existing != null) {
            log("Using existing folder '${folderName}' (id=${existing.id})")
            return existing.id
        }
        val encryptedFolderName = encryptIfEnabled(folderName)
        log("Creating folder '${folderName}' (encrypted=${isEncryptionEnabled})")
        val createResponse = httpClient.post(foldersUrl) {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(FolderRequest(encryptedFolderName))
        }
        if (!createResponse.status.isSuccess()) {
            val body = runCatching { createResponse.body<String>() }.getOrDefault("")
            log("ERROR Failed to create folder (${createResponse.status}): $body")
            throw IllegalStateException(
                "Failed to create folder (${createResponse.status}) at $foldersUrl: $body"
            )
        }
        val created: FolderResponse = createResponse.body()
        log("Created folder '${folderName}' (id=${created.id})")
        return created.id
    }

    private suspend fun fetchAllCiphers(
        creds: BitwardenCredentials,
        token: String
    ): List<VaultItemResponse> {
        val ciphersUrl = "${creds.apiBaseUrl}/ciphers"
        log("Fetching all ciphers from $ciphersUrl")
        val response = httpClient.get(ciphersUrl) {
            bearerAuth(token)
        }
        if (!response.status.isSuccess()) {
            val body = runCatching { response.body<String>() }.getOrDefault("")
            log("ERROR Failed to fetch ciphers (${response.status}): $body")
            throw IllegalStateException(
                "Failed to fetch ciphers (${response.status}) at $ciphersUrl: $body"
            )
        }
        val items: VaultListResponse = response.body()
        log("Fetched ${items.data.size} ciphers")
        return items.data
    }

    private suspend fun fetchVaultItems(
        creds: BitwardenCredentials,
        token: String,
        folderName: String
    ): List<VaultItemResponse> {
        val folderId = getOrCreateFolder(creds, token, folderName)
        val items = fetchAllCiphers(creds, token)
        // Accept both Login (1) and Secure Note (2) for backward compatibility
        return items.filter { it.folderId == folderId && it.type in listOf(1, 2) }
    }

    @Serializable
    private data class SecureNoteType(val type: Int = 0)

    @Serializable
    private data class LoginUri(
        val uri: String?,
        val match: Int? = null // null = default matching
    )

    @Serializable
    private data class LoginData(
        val uris: List<LoginUri>? = null,
        val username: String? = null,
        val password: String? = null,
        val totp: String? = null
    )

    /** Body for PUT /ciphers/{id} and the inner cipher for POST /ciphers/create. */
    @Serializable
    private data class CipherRequest(
        val type: Int,
        val name: String,
        val notes: String? = null,
        val folderId: String? = null,
        val organizationId: String? = null,
        val favorite: Boolean = false,
        val reprompt: Int = 0,
        val login: LoginData? = null,
        val secureNote: SecureNoteType? = null,
        val card: kotlinx.serialization.json.JsonObject? = null,
        val identity: kotlinx.serialization.json.JsonObject? = null,
        val fields: List<kotlinx.serialization.json.JsonObject>? = null
    )

    /**
     * Wrapper for POST /ciphers/create (new ciphers).
     * The Bitwarden API requires folderId and collectionIds at the wrapper level,
     * NOT inside the cipher object, when creating new items.
     */
    @Serializable
    private data class CipherCreateRequest(
        val cipher: CipherRequest,
        val folderId: String? = null,
        val collectionIds: List<String>? = null
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
            // Match by bookmark ID in the serialized notes, not by display name.
            // When encryption is enabled, notes are encrypted on the server — decrypt to match.
            // Search ALL ciphers of type 2 (not just this folder) to find items that may
            // have been created without a folder previously.
            val existingId = allCiphers
                .filter { it.type in listOf(1, 2) }
                .firstOrNull { cipher ->
                    cipher.notes?.let { notes ->
                        runCatching {
                            val decryptedNotes = decryptIfEnabled(notes)
                            json.decodeFromString(Bookmark.serializer(), decryptedNotes).id
                        }.getOrNull() == item.id
                    } ?: false
                }?.id

            val isLogin = item.type == 1
            val cipherBody = CipherRequest(
                type = item.type,
                name = encryptIfEnabled(item.name),
                notes = item.notes?.let { encryptIfEnabled(it) },
                folderId = folderId,
                favorite = item.isFavorite,
                login = if (isLogin && item.url != null) LoginData(
                    uris = listOf(LoginUri(uri = encryptIfEnabled(item.url)))
                ) else null,
                secureNote = if (!isLogin) SecureNoteType() else null
            )
            log("Upserting cipher '${item.name}' (existingId=$existingId, folderId=$folderId, encrypted=$isEncryptionEnabled)")

            val response: HttpResponse = if (existingId != null) {
                // UPDATE: PUT with folderId inside the cipher body
                httpClient.put("${creds.apiBaseUrl}/ciphers/$existingId") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(cipherBody)
                }
            } else {
                // CREATE: POST to /ciphers with folderId in the cipher body
                httpClient.post("${creds.apiBaseUrl}/ciphers") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody(cipherBody)
                }
            }
            if (!response.status.isSuccess()) {
                val respBody = runCatching { response.body<String>() }.getOrDefault("")
                log("ERROR Failed to upsert cipher '${item.name}' (${response.status}): $respBody")
                throw IllegalStateException(
                    "Failed to upsert cipher '${item.name}' (${response.status}): $respBody"
                )
            }
            log("Upserted cipher '${item.name}' (${if (existingId != null) "updated" else "created"})")
    }

    // endregion
}

/**
 * Factory function to create a [KtorBitwardenSyncService] with a default HTTP client.
 */
fun createBitwardenSyncService(): KtorBitwardenSyncService {
    val client = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
                explicitNulls = true
            })
        }
    }
    return KtorBitwardenSyncService(client)
}
