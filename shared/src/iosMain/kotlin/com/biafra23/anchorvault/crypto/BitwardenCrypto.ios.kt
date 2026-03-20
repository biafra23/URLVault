package com.biafra23.anchorvault.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.CCKeyDerivationPBKDF
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreCrypto.kCCKeySizeAES256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCPBKDF2
import platform.CoreCrypto.kCCPRFHmacAlgSHA256
import platform.CoreCrypto.kCCSuccess
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.Security.SecRandomCopyBytes
import platform.Security.kSecRandomDefault
import platform.posix.size_tVar

@OptIn(ExperimentalForeignApi::class)
actual object CryptoProvider {
    actual fun pbkdf2Sha256(password: ByteArray, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray {
        val derivedKey = ByteArray(keyLengthBytes)
        val passwordString = password.decodeToString()
        salt.usePinned { saltPin ->
            derivedKey.usePinned { keyPin ->
                CCKeyDerivationPBKDF(
                    kCCPBKDF2,
                    passwordString,
                    password.size.convert(),
                    saltPin.addressOf(0).reinterpret<UByteVar>(),
                    salt.size.convert(),
                    kCCPRFHmacAlgSHA256,
                    iterations.toUInt(),
                    keyPin.addressOf(0).reinterpret<UByteVar>(),
                    keyLengthBytes.convert()
                )
            }
        }
        return derivedKey
    }

    actual fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = ByteArray(CC_SHA256_DIGEST_LENGTH)
        key.usePinned { keyPin ->
            data.usePinned { dataPin ->
                mac.usePinned { macPin ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPin.addressOf(0),
                        key.size.convert(),
                        dataPin.addressOf(0),
                        data.size.convert(),
                        macPin.addressOf(0)
                    )
                }
            }
        }
        return mac
    }

    actual fun aes256CbcDecrypt(key: ByteArray, iv: ByteArray, ciphertext: ByteArray): ByteArray = memScoped {
        val outputSize = ciphertext.size + kCCKeySizeAES256.toInt()
        val output = ByteArray(outputSize)
        val dataOutMoved = alloc<size_tVar>()
        key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                ciphertext.usePinned { ctPin ->
                    output.usePinned { outPin ->
                        val result = CCCrypt(
                            kCCDecrypt,
                            kCCAlgorithmAES128,
                            kCCOptionPKCS7Padding,
                            keyPin.addressOf(0),
                            kCCKeySizeAES256.convert(),
                            ivPin.addressOf(0),
                            ctPin.addressOf(0),
                            ciphertext.size.convert(),
                            outPin.addressOf(0),
                            outputSize.convert(),
                            dataOutMoved.ptr
                        )
                        require(result == kCCSuccess) { "AES decrypt failed with code $result" }
                    }
                }
            }
        }
        output.copyOfRange(0, dataOutMoved.value.toInt())
    }

    actual fun aes256CbcEncrypt(key: ByteArray, iv: ByteArray, plaintext: ByteArray): ByteArray = memScoped {
        val outputSize = plaintext.size + kCCKeySizeAES256.toInt()
        val output = ByteArray(outputSize)
        val dataOutMoved = alloc<size_tVar>()
        key.usePinned { keyPin ->
            iv.usePinned { ivPin ->
                plaintext.usePinned { ptPin ->
                    output.usePinned { outPin ->
                        val result = CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES128,
                            kCCOptionPKCS7Padding,
                            keyPin.addressOf(0),
                            kCCKeySizeAES256.convert(),
                            ivPin.addressOf(0),
                            ptPin.addressOf(0),
                            plaintext.size.convert(),
                            outPin.addressOf(0),
                            outputSize.convert(),
                            dataOutMoved.ptr
                        )
                        require(result == kCCSuccess) { "AES encrypt failed with code $result" }
                    }
                }
            }
        }
        output.copyOfRange(0, dataOutMoved.value.toInt())
    }

    actual fun randomBytes(length: Int): ByteArray {
        val bytes = ByteArray(length)
        bytes.usePinned { pin ->
            SecRandomCopyBytes(kSecRandomDefault, length.convert(), pin.addressOf(0))
        }
        return bytes
    }
}
