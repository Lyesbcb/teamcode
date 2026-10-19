package me.nathanfallet.teamcode.cli.platform

import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

actual fun writePrivateFile(path: String, content: String) {
    writePrivate(path, content, replace = true)
}
actual fun createPrivateFile(path: String, content: String): Boolean = writePrivate(path, content, replace = false)

private fun writePrivate(path: String, content: String, replace: Boolean): Boolean {
    val destination = Path.of(path)
    val parent = destination.parent
    require(!Files.isSymbolicLink(parent) && !Files.isSymbolicLink(destination))
    Files.createDirectories(parent)
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
    val temporary = Files.createTempFile(parent, ".teamcode-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        Files.writeString(temporary, content)
        if (replace) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        else try { Files.createLink(destination, temporary) } catch (_: FileAlreadyExistsException) { return false }
        return true
    } finally { Files.deleteIfExists(temporary) }
}
