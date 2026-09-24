package com.projectfuture.browser.browser

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Exercises the actual AES/GCM encrypt+decrypt path used by CredentialStore.
 * The Android Keystore provider doesn't exist in a plain JVM unit test, so
 * these use an in-memory KeyGenerator-produced AES-256 key instead of a
 * Keystore-resident one - CredentialCipher doesn't know or care where its
 * key comes from, so this is a faithful test of the actual cipher logic.
 */
class CredentialCipherTest {

    private fun freshKey(): SecretKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    @Test fun decryptRecoversTheOriginalPlaintext() {
        val key = freshKey()
        val cipher = CredentialCipher { key }
        val plaintext = "{\"u\":\"alice\",\"p\":\"hunter2\"}".toByteArray(Charsets.UTF_8)

        val ciphertext = cipher.encrypt(plaintext)
        val recovered = cipher.decrypt(ciphertext)

        assertArrayEquals(plaintext, recovered)
    }

    @Test fun ciphertextDoesNotContainThePlaintextPassword() {
        val key = freshKey()
        val cipher = CredentialCipher { key }
        val plaintext = "supersecretpassword123".toByteArray(Charsets.UTF_8)

        val ciphertext = cipher.encrypt(plaintext)

        val ciphertextAsLatin1 = String(ciphertext, Charsets.ISO_8859_1)
        assertEquals(false, ciphertextAsLatin1.contains("supersecretpassword123"))
    }

    @Test fun eachEncryptionUsesAFreshIvSoRepeatedCallsProduceDifferentCiphertext() {
        val key = freshKey()
        val cipher = CredentialCipher { key }
        val plaintext = "same plaintext every time".toByteArray(Charsets.UTF_8)

        val first = cipher.encrypt(plaintext)
        val second = cipher.encrypt(plaintext)

        assertNotEquals(first.toList(), second.toList())
        // But both still decrypt back to the same plaintext.
        assertArrayEquals(plaintext, cipher.decrypt(first))
        assertArrayEquals(plaintext, cipher.decrypt(second))
    }

    @Test(expected = Exception::class)
    fun decryptingWithTheWrongKeyFails() {
        val cipher = CredentialCipher { freshKey() }
        val ciphertext = cipher.encrypt("secret".toByteArray(Charsets.UTF_8))
        // A second CredentialCipher backed by a different key must not be able to decrypt it -
        // GCM's authentication tag check should reject it rather than silently returning garbage.
        val wrongKeyCipher = CredentialCipher { freshKey() }
        wrongKeyCipher.decrypt(ciphertext)
    }

    @Test(expected = IllegalArgumentException::class)
    fun decryptingATooShortBlobFails() {
        val cipher = CredentialCipher { freshKey() }
        cipher.decrypt(ByteArray(4))
    }
}
