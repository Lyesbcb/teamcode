@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.curl.Curl
import kotlinx.cinterop.*
import platform.posix.getenv

actual fun environment(name: String): String? = getenv(name)?.toKString()
actual fun httpEngine(): HttpClientEngineFactory<*> = Curl

actual fun secureRandomBytes(count: Int): ByteArray {
    require(count in 1..1024)
    val path = okio.Path.Companion.run { "/dev/urandom".toPath() }
    return okio.FileSystem.SYSTEM.read(path) { readByteArray(count.toLong()) }
}
