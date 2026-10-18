package me.nathanfallet.teamcode.infrastructure.secrets

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Versioned AES-256-GCM envelope; context binds the ciphertext to a stored account/team identity. */
class CredentialCipher(key: ByteArray, private val random: SecureRandom = SecureRandom()) {
    private val secret = SecretKeySpec(key.copyOf().also { require(it.size == 32) { "An AES-256 key must be exactly 32 bytes." } }, "AES")

    fun encrypt(plaintext: ByteArray, context: String): String {
        require(context.isNotBlank())
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(128, nonce))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext)
        return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + encrypted)
    }

    fun decrypt(envelope: String, context: String): ByteArray {
        require(context.isNotBlank())
        try {
            require(envelope.startsWith("v1:"))
            val bytes = Base64.getUrlDecoder().decode(envelope.removePrefix("v1:"))
            require(bytes.size >= 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
            return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        } catch (_: IllegalArgumentException) {
            throw CredentialDecryptionException()
        } catch (_: java.security.GeneralSecurityException) {
            throw CredentialDecryptionException()
        }
    }
}

class CredentialDecryptionException : Exception("Unable to decrypt stored credentials.")
