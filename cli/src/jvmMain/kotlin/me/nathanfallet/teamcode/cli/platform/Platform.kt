package me.nathanfallet.teamcode.cli.platform


actual fun environment(name: String): String? = System.getenv(name)
actual fun platformArguments(arguments: Array<String>): Array<String> = arguments

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count.also { require(it in 1..1024) }).also(java.security.SecureRandom()::nextBytes)
