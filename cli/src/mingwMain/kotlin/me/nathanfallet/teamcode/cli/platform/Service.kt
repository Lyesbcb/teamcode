@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_user_sid
import platform.windows.*

actual fun servicePlatform(): ServicePlatform = memScoped {
    val buffer = allocArray<UShortVar>(32_768)
    val length = GetModuleFileNameW(null, buffer, 32_768u)
    check(length > 0u && length < 32_768u)
    val sid = checkNotNull(teamcode_windows_user_sid())
    try { ServicePlatform("windows", buffer.toKStringFromUtf16(), sid.toKStringFromUtf16()) }
    finally { LocalFree(sid) }
}

// Task Scheduler uses a private cooperative stop marker; no console or privileged process kill is required.
actual fun installServiceSignalHandlers() = Unit
actual fun serviceStopRequested(): Boolean = false
