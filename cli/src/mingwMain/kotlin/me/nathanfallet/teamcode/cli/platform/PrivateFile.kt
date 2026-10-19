@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import me.nathanfallet.teamcode.cli.CliOperationException
import me.nathanfallet.teamcode.cli.interop.windows.teamcode_windows_write_private
import okio.FileSystem
import okio.Path.Companion.toPath

actual fun writePrivateFile(path: String, content: String) {
    writePrivate(path, content, replace = true)
}
actual fun createPrivateFile(path: String, content: String): Boolean = writePrivate(path, content, replace = false)

private fun writePrivate(path: String, content: String, replace: Boolean): Boolean {
    val destination = path.toPath()
    val parent = requireNotNull(destination.parent)
    require(destination.isAbsolute)
    parent.parent?.let { FileSystem.SYSTEM.createDirectories(it) }
    val suffix = secureRandomBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val normalized = destination.toString()
    val temporary = "$normalized.$suffix.tmp"
    val contentBytes = content.encodeToByteArray()
    require(contentBytes.size <= 1024 * 1024)
    // The adapter creates the immediate directory/file with a protected owner-only DACL,
    // rejects reparse points/foreign owners, and replaces the destination atomically.
    return memScoped {
        contentBytes.usePinned { bytes ->
            val failure = teamcode_windows_write_private(normalized.wcstr.ptr, parent.toString().wcstr.ptr,
                temporary.wcstr.ptr, if (contentBytes.isEmpty()) null else bytes.addressOf(0), contentBytes.size.toUInt(), if (replace) 1 else 0)
            if (failure == -1 && !replace) return@usePinned false
            if (failure != 0) throw CliOperationException("windows_private_file_$failure")
            true
        }
    }
}
