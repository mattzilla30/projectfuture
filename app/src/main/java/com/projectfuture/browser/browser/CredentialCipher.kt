package com.projectfuture.browser.browser

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256/GCM encrypt+decrypt for the credential store, parameterized over
 * how the [SecretKey] is obtained. Production code (see [CredentialStore])
 * supplies a non-exportable key generated inside the `AndroidKeyStore`
 * provider, so the key material itself never touches this class, disk, or
 * a debugger's heap dump. JVM unit tests instead supply a plain in-memory
 * `KeyGenerator`-produced AES key - the Android Keystore provider doesn't
 * exist off-device, but AES/GCM's math is identical either way, so this is
 * a faithful test of the actual encrypt/decrypt path.
 *
 * Wire format is `iv (12 bytes) || ciphertext+tag`, a fresh random IV per
 * call - GCM's confidentiality guarantee depends on never reusing an
 * (key, IV) pair, so encrypt() draws a new one every time rather than
 * accepting one from the caller.
 */
class CredentialCipher(private val keyProvider: () -> SecretKey) {

    fun encrypt(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    fun decrypt(ivAndCiphertext: ByteArray): ByteArray {
        require(ivAndCiphertext.size > IV_LENGTH_BYTES) { "ciphertext blob too short to contain an IV" }
        val iv = ivAndCiphertext.copyOfRange(0, IV_LENGTH_BYTES)
        val ciphertext = ivAndCiphertext.copyOfRange(IV_LENGTH_BYTES, ivAndCiphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, keyProvider(), GCMParameterSpec(TAG_LENGTH_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH_BYTES = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
