package me.nathanfallet.teamcode.cli.platform


actual fun environment(name: String): String? = System.getenv(name)

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count.also { require(it in 1..1024) }).also(java.security.SecureRandom()::nextBytes)
