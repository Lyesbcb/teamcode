package me.nathanfallet.teamcode.cli.platform

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

actual fun writePrivateFile(path: String, content: String) {
    val destination = Path.of(path)
    val parent = destination.parent
    require(!Files.isSymbolicLink(parent) && !Files.isSymbolicLink(destination))
    Files.createDirectories(parent)
    Files.setPosixFilePermissions(parent, PosixFilePermissions.fromString("rwx------"))
    val temporary = Files.createTempFile(parent, ".teamcode-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
    try {
        Files.writeString(temporary, content)
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(temporary) }
}
