@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*

actual fun writePrivateFile(path: String, content: String) {
    writePrivate(path, content, replace = true)
}
actual fun createPrivateFile(path: String, content: String): Boolean = writePrivate(path, content, replace = false)

private fun writePrivate(path: String, content: String, replace: Boolean): Boolean {
    val fs = FileSystem.SYSTEM
    val destination = path.toPath()
    val parent = requireNotNull(destination.parent)
    require(fs.metadataOrNull(parent)?.symlinkTarget == null) { "Configuration directory cannot be a symbolic link." }
    fs.createDirectories(parent)
    check(chmod(parent.toString(), 448u) == 0) { "Cannot restrict configuration directory permissions." }
    require(fs.metadataOrNull(destination)?.symlinkTarget == null) { "Configuration file cannot be a symbolic link." }
    val suffix = secureRandomBytes(12).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    val temp = "$path.${getpid()}.$suffix.tmp"
    val fd = open(temp, O_WRONLY or O_CREAT or O_EXCL, 384u)
    check(fd >= 0) { "Cannot create private configuration file." }
    try {
        val bytes = content.encodeToByteArray()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val count = write(fd, pinned.addressOf(offset), (bytes.size - offset).convert())
                check(count > 0) { "Cannot write configuration." }
                offset += count.toInt()
            }
        }
        check(fsync(fd) == 0) { "Cannot sync configuration." }
        if (replace) {
            check(rename(temp, path) == 0) { "Cannot replace configuration." }
            return true
        }
        if (link(temp, path) == 0) return true
        check(errno == EEXIST) { "Cannot create configuration without replacing an existing file." }
        return false
    } finally {
        close(fd)
        unlink(temp)
    }
}
