package me.nathanfallet.teamcode.cli.config

import kotlin.uuid.Uuid
import me.nathanfallet.teamcode.cli.platform.createPrivateFile
import okio.FileSystem
import okio.Path.Companion.toPath

/** A non-secret logical installation ID; deliberately survives logout and server changes. */
class InstallationStore(private val path: String = requireNotNull(configPath().toPath().parent).resolve("installation-id").toString()) {
    fun id(): String {
        read()?.let { return it }
        createPrivateFile(path, Uuid.random().toString())
        return checkNotNull(read()) { "The installation identity could not be created." }
    }
    private fun read(): String? {
        val file = path.toPath()
        val metadata = FileSystem.SYSTEM.metadataOrNull(file) ?: return null
        require(metadata.symlinkTarget == null && metadata.isRegularFile && (metadata.size ?: 0) <= 64) {
            "The installation identity must be a small regular file, not a symbolic link."
        }
        val id = FileSystem.SYSTEM.read(file) { readUtf8() }
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)) { "Invalid TeamCode installation identity." }
        return id
    }
}
