@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.process.teamcode_hook_input

internal actual fun readHookInput(): ByteArray? {
    val bytes = ByteArray(65_537)
    val size = bytes.usePinned { teamcode_hook_input(it.addressOf(0).reinterpret(), bytes.size) }
    return if (size < 0) null else bytes.copyOf(size)
}
