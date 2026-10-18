@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.posix.*

actual fun writePrivateFile(path: String, content: String) {
    val fs = FileSystem.SYSTEM
    val destination = path.toPath()
    val parent = requireNotNull(destination.parent)
    require(fs.metadataOrNull(parent)?.symlinkTarget == null) { "Configuration directory cannot be a symbolic link." }
    fs.createDirectories(parent)
    check(chmod(parent.toString(), 448u) == 0) { "Cannot restrict configuration directory permissions." }
    require(fs.metadataOrNull(destination)?.symlinkTarget == null) { "Configuration file cannot be a symbolic link." }
    val temp = "$path.${getpid()}.tmp"
    val fd = open(temp, O_WRONLY or O_CREAT or O_EXCL, 384u)
    check(fd >= 0) { "Cannot create private configuration file." }
    var renamed = false
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
        check(rename(temp, path) == 0) { "Cannot replace configuration." }
        renamed = true
    } finally {
        close(fd)
        if (!renamed) unlink(temp)
    }
}
