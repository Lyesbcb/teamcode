package me.nathanfallet.teamcode.infrastructure.secrets

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

class Passwords {
    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "pbkdf2-sha256:600000:${encode(salt)}:${encode(derive(password, salt, 600_000))}"
    }
    fun verify(password: String, encoded: String): Boolean = try {
        val parts = encoded.split(':')
        require(parts.size == 4 && parts[0] == "pbkdf2-sha256")
        val iterations = parts[1].toInt().also { require(it in 100_000..1_000_000) }
        MessageDigest.isEqual(derive(password, Base64.getUrlDecoder().decode(parts[2]), iterations), Base64.getUrlDecoder().decode(parts[3]))
    } catch (_: IllegalArgumentException) { false }
    private fun derive(password: String, salt: ByteArray, rounds: Int): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, rounds, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
    }
    companion object {
        private val random = SecureRandom()
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        fun secret(): String = encode(ByteArray(32).also(random::nextBytes))
        fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
