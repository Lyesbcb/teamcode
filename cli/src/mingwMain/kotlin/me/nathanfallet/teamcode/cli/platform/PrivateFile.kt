@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_write_private
import okio.FileSystem
import okio.Path.Companion.toPath

actual fun writePrivateFile(path: String, content: String) {
    val destination = path.toPath()
    val parent = requireNotNull(destination.parent)
    require(destination.isAbsolute)
    parent.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    val suffix = secureRandomBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val temporary = "$path.$suffix.tmp"
    val contentBytes = content.encodeToByteArray()
    require(contentBytes.size <= 1024 * 1024)
    // The adapter creates the immediate directory/file with a protected owner-only DACL,
    // rejects reparse points/foreign owners, and replaces the destination atomically.
    memScoped {
        contentBytes.usePinned { bytes ->
            check(teamcode_windows_write_private(path.wcstr.ptr, parent.toString().wcstr.ptr,
                temporary.wcstr.ptr, if (contentBytes.isEmpty()) null else bytes.addressOf(0), contentBytes.size.toUInt()) != 0) {
                "Cannot write private Windows configuration. Check ownership and NTFS permissions."
            }
        }
    }
}
