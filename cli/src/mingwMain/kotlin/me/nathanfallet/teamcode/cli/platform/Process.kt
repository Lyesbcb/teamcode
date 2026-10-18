@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package me.nathanfallet.teamcode.cli.platform

import kotlinx.cinterop.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.cli.interop.windows.*
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.windows.*

private fun executable(command: String): String? {
    val fs = FileSystem.SYSTEM
    val names = if (command.endsWith(".exe", true)) listOf(command) else listOf("$command.exe")
    val roots = if (command.toPath().isAbsolute || '/' in command || '\\' in command) listOf("")
        else environment("PATH").orEmpty().split(';').filter { it.isNotBlank() }.map { it.trim('"') + "\\" }
    return roots.flatMap { root -> names.map { root + it } }.firstOrNull { fs.metadataOrNull(it.toPath())?.isRegularFile == true }
}

/** Recognize the standard npm wrappers without evaluating .cmd files or invoking a shell. */
private fun resolve(command: String, arguments: List<String>): Pair<String, List<String>> {
    executable(command)?.let { return it to arguments }
    val script = when (command) {
        "codex" -> "node_modules/@openai/codex/bin/codex.js"
        "claude" -> "node_modules/@anthropic-ai/claude-code/cli.js"
        else -> error("Install the requested executable and make it available on PATH.")
    }
    for (directory in environment("PATH").orEmpty().split(';').filter { it.isNotBlank() }) {
        val root = directory.trim('"').toPath()
        val file = root.resolve(script)
        if (FileSystem.SYSTEM.metadataOrNull(root.resolve("$command.cmd"))?.isRegularFile == true &&
            FileSystem.SYSTEM.metadataOrNull(file)?.isRegularFile == true) {
            val node = executable(root.resolve("node.exe").toString()) ?: executable("node") ?: error("Node.js is required for this npm installation.")
            return node to (listOf(file.toString()) + arguments)
        }
    }
    error("Install the native agent or its standard npm package and make it available on PATH.")
}

actual suspend fun runInteractiveProcess(command: String, arguments: List<String>, overrides: Map<String, String?>, quiet: Boolean): Int {
    val (application, actualArguments) = resolve(command, arguments)
    val line = (listOf(application) + actualArguments).joinToString(" ", transform = ::quoteWindowsArgument)
    require(line.length < 32_767)
    val child = memScoped {
        val values = mutableMapOf<String, Pair<String, String>>()
        val inherited = checkNotNull(GetEnvironmentStringsW())
        try {
            var offset = 0
            while (inherited[offset] != 0.toUShort()) {
                val entry = (inherited + offset)!!.toKStringFromUtf16()
                offset += entry.length + 1
                val separator = entry.indexOf('=', startIndex = if (entry.startsWith('=')) 1 else 0)
                if (separator > 0) {
                    val name = entry.substring(0, separator)
                    values[name.uppercase()] = name to entry.substring(separator + 1)
                }
            }
        } finally { FreeEnvironmentStringsW(inherited) }
        overrides.forEach { (name, value) ->
            require(name.isNotEmpty() && name.none { it == '=' || it == '\u0000' } && value?.contains('\u0000') != true)
            if (value == null) values.remove(name.uppercase()) else values[name.uppercase()] = name to value
        }
        val block = values.entries.sortedBy { it.key }.joinToString("\u0000", postfix = "\u0000\u0000") { "${it.value.first}=${it.value.second}" }
        checkNotNull(teamcode_windows_spawn(application.wcstr.ptr, line.wcstr.ptr, block.wcstr.ptr, if (quiet) 1 else 0)) {
            "Cannot launch the requested executable."
        }
    }
    var completed = false
    try {
        while (true) {
            val code = memScoped {
                val result = alloc<UIntVar>()
                when (teamcode_windows_poll(child, result.ptr)) {
                    0 -> null
                    1 -> { completed = true; result.value.toInt() }
                    else -> error("Cannot wait for the requested process.")
                }
            }
            if (code != null) return code
            delay(50)
        }
    } finally {
        if (!completed) teamcode_windows_terminate(child)
        teamcode_windows_close(child)
    }
}
