@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import platform.posix.getenv

actual fun environment(name: String): String? = getenv(name)?.toKString()

actual fun secureRandomBytes(count: Int): ByteArray = error("Windows secure random support is not released yet.")
