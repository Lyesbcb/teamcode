package me.nathanfallet.teamcode.infrastructure

import me.nathanfallet.teamcode.infrastructure.secrets.*
import kotlin.test.*

class CredentialCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    @Test fun `envelopes use fresh nonces and retain authenticated plaintext`() {
        val cipher = CredentialCipher(key)
        val plaintext = "synthetic-secret".toByteArray()
        val a = cipher.encrypt(plaintext, "team/account")
        val b = cipher.encrypt(plaintext, "team/account")
        assertNotEquals(a, b)
        assertFalse(a.contains("synthetic-secret"))
        assertContentEquals(plaintext, cipher.decrypt(a, "team/account"))
    }
    @Test fun `ciphertext cannot be moved to another identity or decrypted with another key`() {
        val cipher = CredentialCipher(key)
        val encrypted = cipher.encrypt("synthetic-secret".toByteArray(), "team/account")
        assertFailsWith<CredentialDecryptionException> { cipher.decrypt(encrypted, "other/account") }
        assertFailsWith<CredentialDecryptionException> { CredentialCipher(ByteArray(32) { 42 }).decrypt(encrypted, "team/account") }
        assertFailsWith<CredentialDecryptionException> { cipher.decrypt("v1:invalid-envelope", "team/account") }
    }
}
