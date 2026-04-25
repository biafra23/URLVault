package com.biafra23.anchorvault.android.database

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Manages the SQLCipher database passphrase using the Android Keystore.
 *
 * A random 32-byte passphrase is generated on first use and encrypted with a
 * hardware-backed AES-GCM key stored in the Android Keystore. The encrypted
 * passphrase is persisted in SharedPreferences.
 */
object DatabaseKeyManager {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "AnchorVaultDbKey"
    private const val PREFS_NAME = "anchorvault_prefs"
    private const val PREF_ENCRYPTED_KEY = "encrypted_db_key"
    private const val PREF_IV = "db_key_iv"
    private const val GCM_TAG_LENGTH = 128

    /**
     * Returns the database passphrase, generating and storing it on first call.
     */
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedKey = prefs.getString(PREF_ENCRYPTED_KEY, null)
        val iv = prefs.getString(PREF_IV, null)

        return if (encryptedKey != null && iv != null) {
            decryptPassphrase(
                Base64.decode(encryptedKey, Base64.DEFAULT),
                Base64.decode(iv, Base64.DEFAULT)
            )
        } else {
            val newPassphrase = generateRandomPassphrase()
            val (encrypted, newIv) = encryptPassphrase(newPassphrase)
            prefs.edit()
                .putString(PREF_ENCRYPTED_KEY, Base64.encodeToString(encrypted, Base64.DEFAULT))
                .putString(PREF_IV, Base64.encodeToString(newIv, Base64.DEFAULT))
                .apply()
            newPassphrase
        }
    }

    private fun generateRandomPassphrase(): ByteArray {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        return if (keyStore.containsAlias(KEY_ALIAS)) {
            (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
        } else {
            val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            keyGen.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
            keyGen.generateKey()
        }
    }

    private fun encryptPassphrase(passphrase: ByteArray): Pair<ByteArray, ByteArray> {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(passphrase)
        return Pair(encrypted, cipher.iv)
    }

    private fun decryptPassphrase(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val key = getOrCreateSecretKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }
}
