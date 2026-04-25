package com.biafra23.anchorvault.crypto

/**
 * Platform-specific cryptographic primitives needed for the Bitwarden encryption protocol.
 */
expect object CryptoProvider {
    /** PBKDF2-HMAC-SHA256 key derivation. */
    fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray

    /** HMAC-SHA256. */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray

    /** AES-256-CBC decrypt (PKCS7 padding). */
    fun aes256CbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray

    /** AES-256-CBC encrypt (PKCS7 padding). Returns raw ciphertext. */
    fun aes256CbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray

    /** Generate [length] cryptographically secure random bytes. */
    fun randomBytes(length: Int): ByteArray
}

/**
 * Bitwarden vault encryption/decryption using the standard Bitwarden protocol.
 *
 * Encryption format: `2.<base64(iv)>|<base64(ciphertext)>|<base64(mac)>`
 * where type 2 = AES-256-CBC + HMAC-SHA256.
 */
object BitwardenEncryption {

    /**
     * Derives the master key from the user's master password and email.
     * masterKey = PBKDF2-SHA256(password, lowercase(email), iterations, 32 bytes)
     */
    fun deriveMasterKey(masterPassword: String, email: String, kdfIterations: Int): ByteArray {
        return CryptoProvider.pbkdf2Sha256(
            password = masterPassword.encodeToByteArray(),
            salt = email.lowercase().encodeToByteArray(),
            iterations = kdfIterations,
            keyLengthBytes = 32
        )
    }

    /**
     * Stretches the master key using HKDF-Expand-SHA256 into an encryption key and MAC key.
     * Returns Pair(encKey: 32 bytes, macKey: 32 bytes).
     */
    fun stretchMasterKey(masterKey: ByteArray): Pair<ByteArray, ByteArray> {
        val encKey = hkdfExpand(masterKey, "enc".encodeToByteArray(), 32)
        val macKey = hkdfExpand(masterKey, "mac".encodeToByteArray(), 32)
        return Pair(encKey, macKey)
    }

    /**
     * Decrypts the user's encrypted symmetric key (from the token response `Key` field).
     * The result is 64 bytes: first 32 = vault encKey, last 32 = vault macKey.
     */
    fun decryptEncryptionKey(encryptedKey: String, encKey: ByteArray, macKey: ByteArray): Pair<ByteArray, ByteArray> {
        val decrypted = decryptString(encryptedKey, encKey, macKey)
        require(decrypted.size == 64) {
            "Decrypted symmetric key should be 64 bytes, got ${decrypted.size}"
        }
        return Pair(decrypted.copyOfRange(0, 32), decrypted.copyOfRange(32, 64))
    }

    /**
     * Encrypts a plaintext string into Bitwarden's encrypted format.
     * Returns: `2.<base64(iv)>|<base64(ciphertext)>|<base64(mac)>`
     */
    fun encryptString(plaintext: String, encKey: ByteArray, macKey: ByteArray): String {
        val iv = CryptoProvider.randomBytes(16)
        val ct = CryptoProvider.aes256CbcEncrypt(encKey, iv, plaintext.encodeToByteArray())
        val mac = CryptoProvider.hmacSha256(macKey, iv + ct)
        return "2.${base64Encode(iv)}|${base64Encode(ct)}|${base64Encode(mac)}"
    }

    /**
     * Decrypts a Bitwarden encrypted string (format: `2.iv|ct|mac`).
     */
    fun decryptString(encrypted: String, encKey: ByteArray, macKey: ByteArray): ByteArray {
        val (iv, ct, mac) = parseEncryptedString(encrypted)
        // Verify HMAC
        val computedMac = CryptoProvider.hmacSha256(macKey, iv + ct)
        require(computedMac.contentEquals(mac)) { "HMAC verification failed — wrong master password?" }
        return CryptoProvider.aes256CbcDecrypt(encKey, iv, ct)
    }

    /**
     * Decrypts an encrypted string and returns it as a UTF-8 string.
     */
    fun decryptToString(encrypted: String, encKey: ByteArray, macKey: ByteArray): String {
        return decryptString(encrypted, encKey, macKey).decodeToString()
    }

    private fun parseEncryptedString(encrypted: String): Triple<ByteArray, ByteArray, ByteArray> {
        // Format: "2.base64(iv)|base64(ct)|base64(mac)"
        val typeAndRest = encrypted.split(".", limit = 2)
        require(typeAndRest.size == 2 && typeAndRest[0] == "2") {
            "Only encryption type 2 (AES-256-CBC + HMAC-SHA256) is supported, got: ${typeAndRest.getOrNull(0)}"
        }
        val parts = typeAndRest[1].split("|")
        require(parts.size == 3) { "Expected 3 parts (iv|ct|mac), got ${parts.size}" }
        return Triple(base64Decode(parts[0]), base64Decode(parts[1]), base64Decode(parts[2]))
    }

    /**
     * HKDF-Expand (RFC 5869) using HMAC-SHA256.
     * Note: Bitwarden uses only the expand step, not extract, because the master key
     * from PBKDF2 is already a pseudo-random key.
     */
    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hashLen = 32 // SHA-256 output
        val n = (length + hashLen - 1) / hashLen
        var previousBlock = ByteArray(0)
        val output = ByteArray(length)
        var offset = 0
        for (i in 1..n) {
            val input = previousBlock + info + byteArrayOf(i.toByte())
            previousBlock = CryptoProvider.hmacSha256(prk, input)
            val toCopy = minOf(hashLen, length - offset)
            previousBlock.copyInto(output, offset, 0, toCopy)
            offset += toCopy
        }
        return output
    }
}

// Base64 encode/decode using kotlin.io.encoding (available in Kotlin 1.8+)
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun base64Encode(data: ByteArray): String =
    kotlin.io.encoding.Base64.encode(data)

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun base64Decode(data: String): ByteArray =
    kotlin.io.encoding.Base64.decode(data)
