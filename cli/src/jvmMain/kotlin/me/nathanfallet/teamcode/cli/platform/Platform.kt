package me.nathanfallet.teamcode.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

actual fun environment(name: String): String? = System.getenv(name)
actual fun httpEngine(): HttpClientEngineFactory<*> = CIO

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count.also { require(it in 1..1024) }).also(java.security.SecureRandom()::nextBytes)
