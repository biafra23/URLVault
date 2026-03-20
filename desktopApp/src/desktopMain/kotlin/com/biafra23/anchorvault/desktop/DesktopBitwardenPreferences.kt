package com.biafra23.anchorvault.desktop

import com.biafra23.anchorvault.sync.BitwardenCredentials
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted file-based persistence for Bitwarden credentials on Desktop.
 *
 * Credentials are encrypted with AES-256-GCM. The AES key is stored using the
 * best available OS secure storage:
 *
 * - **macOS**: Keychain (backed by Secure Enclave on Apple Silicon)
 * - **Linux**: Secret Service API (GNOME Keyring / KDE Wallet) via `secret-tool`
 * - **Fallback**: PKCS12 Java KeyStore file with OS-user-derived password
 */
class DesktopBitwardenPreferences {

    private val appDir: File = run {
        val home = System.getProperty("user.home")
        val dir = File("$home/.anchorvault")
        dir.mkdirs()
        dir
    }

    private val credentialsFile = File(appDir, "credentials.enc")
    private val settingsFile = File(appDir, "settings.json")

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val keyBackend: KeyBackend by lazy { selectKeyBackend() }
    private val secretKey: SecretKey by lazy { keyBackend.loadOrCreateKey() }

    fun saveCredentials(credentials: BitwardenCredentials) {
        val plaintext = json.encodeToString(credentials).toByteArray(Charsets.UTF_8)
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)
        credentialsFile.writeBytes(iv + ciphertext)
    }

    fun loadCredentials(): BitwardenCredentials? {
        if (!credentialsFile.exists()) return null
        return runCatching {
            val data = credentialsFile.readBytes()
            val iv = data.copyOfRange(0, 12)
            val ciphertext = data.copyOfRange(12, data.size)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plaintext = cipher.doFinal(ciphertext)
            json.decodeFromString<BitwardenCredentials>(plaintext.toString(Charsets.UTF_8))
        }.getOrNull()
    }

    fun clearCredentials() {
        if (credentialsFile.exists()) credentialsFile.delete()
    }

    fun saveAutoTagEnabled(enabled: Boolean) {
        val settings = loadSettings().toMutableMap()
        settings["autoTagEnabled"] = enabled.toString()
        settingsFile.writeText(json.encodeToString(settings))
    }

    fun loadAutoTagEnabled(): Boolean {
        return loadSettings()["autoTagEnabled"]?.toBooleanStrictOrNull() ?: false
    }

    private fun loadSettings(): Map<String, String> {
        if (!settingsFile.exists()) return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, String>>(settingsFile.readText())
        }.getOrDefault(emptyMap())
    }

    // region Key backend selection

    private fun selectKeyBackend(): KeyBackend {
        val os = System.getProperty("os.name")?.lowercase() ?: ""
        return when {
            os.contains("mac") -> MacKeychainBackend().takeIf { it.isAvailable() }
            os.contains("linux") -> LinuxSecretServiceBackend().takeIf { it.isAvailable() }
            else -> null
        } ?: Pkcs12KeyStoreBackend(appDir) // fallback for Windows or when native tools aren't available
    }

    // endregion

    companion object {
        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val SERVICE_NAME = "anchorvault"
        private const val ACCOUNT_NAME = "credentials-key"
    }

    // region Key backend interface + implementations

    private interface KeyBackend {
        fun isAvailable(): Boolean
        fun loadOrCreateKey(): SecretKey
    }

    /**
     * macOS Keychain via the `security` CLI.
     * On Apple Silicon, the Keychain is backed by the Secure Enclave.
     */
    private class MacKeychainBackend : KeyBackend {
        override fun isAvailable(): Boolean = runCommand("security", "help") != null

        override fun loadOrCreateKey(): SecretKey {
            // Try to retrieve existing key
            val existing = runCommand(
                "security", "find-generic-password",
                "-a", ACCOUNT_NAME,
                "-s", SERVICE_NAME,
                "-w"
            )
            if (existing != null) {
                return SecretKeySpec(Base64.getDecoder().decode(existing.trim()), "AES")
            }
            // Generate and store new key
            val key = generateAesKey()
            val b64 = Base64.getEncoder().encodeToString(key.encoded)
            val result = runCommand(
                "security", "add-generic-password",
                "-a", ACCOUNT_NAME,
                "-s", SERVICE_NAME,
                "-w", b64,
                "-U" // update if exists
            )
            if (result == null) {
                throw IllegalStateException("Failed to store key in macOS Keychain")
            }
            return key
        }
    }

    /**
     * Linux Secret Service API (GNOME Keyring / KDE Wallet) via `secret-tool`.
     */
    private class LinuxSecretServiceBackend : KeyBackend {
        override fun isAvailable(): Boolean = runCommand("which", "secret-tool") != null

        override fun loadOrCreateKey(): SecretKey {
            // Try to retrieve existing key
            val existing = runCommand(
                "secret-tool", "lookup",
                "application", SERVICE_NAME,
                "type", ACCOUNT_NAME
            )
            if (existing != null && existing.isNotBlank()) {
                return SecretKeySpec(Base64.getDecoder().decode(existing.trim()), "AES")
            }
            // Generate and store new key
            val key = generateAesKey()
            val b64 = Base64.getEncoder().encodeToString(key.encoded)
            // secret-tool reads the secret from stdin
            val stored = runCommandWithStdin(
                input = b64,
                "secret-tool", "store",
                "--label=AnchorVault Credentials Key",
                "application", SERVICE_NAME,
                "type", ACCOUNT_NAME
            )
            if (stored == null) {
                throw IllegalStateException("Failed to store key in Secret Service")
            }
            return key
        }
    }

    /**
     * Fallback: PKCS12 Java KeyStore file with OS-user-derived password.
     */
    private class Pkcs12KeyStoreBackend(private val appDir: File) : KeyBackend {
        private val keystoreFile = File(appDir, "keystore.p12")
        private val keystorePassword: CharArray = run {
            // Derive a keystore password from OS-user identity using PBKDF2 so that
            // the raw PKCS12 file cannot be brute-forced trivially.
            val user = System.getProperty("user.name") ?: "unknown"
            val home = System.getProperty("user.home") ?: "/tmp"
            val base = "AnchorVault:$user:$home"
            val salt = "AnchorVault-PKCS12-salt".toByteArray()
            val spec = javax.crypto.spec.PBEKeySpec(base.toCharArray(), salt, 100_000, 256)
            val skf = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            java.util.Base64.getEncoder().encodeToString(skf.generateSecret(spec).encoded).toCharArray()
        }

        override fun isAvailable(): Boolean = true

        override fun loadOrCreateKey(): SecretKey {
            val keyStore = KeyStore.getInstance("PKCS12")
            if (keystoreFile.exists()) {
                keystoreFile.inputStream().use { keyStore.load(it, keystorePassword) }
                val entry = keyStore.getEntry("anchorvault_key", KeyStore.PasswordProtection(keystorePassword))
                if (entry is KeyStore.SecretKeyEntry) {
                    return entry.secretKey
                }
            }
            val key = generateAesKey()
            keyStore.load(null, keystorePassword)
            keyStore.setEntry(
                "anchorvault_key",
                KeyStore.SecretKeyEntry(key),
                KeyStore.PasswordProtection(keystorePassword)
            )
            keystoreFile.outputStream().use { keyStore.store(it, keystorePassword) }
            return key
        }
    }

    // endregion

    // region Utility functions (in companion-accessible scope via top-level private)
}

private fun generateAesKey(): SecretKey {
    val keyGen = KeyGenerator.getInstance("AES")
    keyGen.init(256, SecureRandom())
    return keyGen.generateKey()
}

private fun runCommand(vararg command: String): String? {
    return try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode == 0) output else null
    } catch (e: Exception) {
        System.err.println("runCommand failed [${command.joinToString(" ")}]: ${e.message}")
        null
    }
}

private fun runCommandWithStdin(input: String, vararg command: String): String? {
    return try {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use { it.write(input) }
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode == 0) output else null
    } catch (e: Exception) {
        System.err.println("runCommandWithStdin failed [${command.joinToString(" ")}]: ${e.message}")
        null
    }
}
