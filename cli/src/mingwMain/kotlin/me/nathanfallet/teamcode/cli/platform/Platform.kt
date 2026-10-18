@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import platform.windows.GetEnvironmentVariableW
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_random
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_arguments
import platform.windows.LocalFree

/** Read the Unicode command line directly instead of depending on the workstation's ANSI code page. */
actual fun platformArguments(arguments: Array<String>): Array<String> = memScoped {
    val count = alloc<IntVar>()
    val values = checkNotNull(teamcode_windows_arguments(count.ptr))
    try { Array((count.value - 1).coerceAtLeast(0)) { checkNotNull(values[it + 1]).toKStringFromUtf16() } }
    finally { LocalFree(values) }
}

actual fun environment(name: String): String? = memScoped {
    val length = GetEnvironmentVariableW(name, null, 0u)
    if (length == 0u) return@memScoped null
    require(length <= 65_536u)
    val buffer = allocArray<UShortVar>(length.toInt())
    val read = GetEnvironmentVariableW(name, buffer, length)
    check(read > 0u && read < length)
    buffer.toKStringFromUtf16()
}

actual fun secureRandomBytes(count: Int): ByteArray {
    require(count in 1..1024)
    return ByteArray(count).also { bytes -> bytes.usePinned {
        check(teamcode_windows_random(it.addressOf(0), count.toUInt()) != 0) { "Windows could not generate a secure token." }
    } }
}
