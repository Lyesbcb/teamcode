package me.nathanfallet.teamcode.cli.launch

import kotlinx.serialization.json.JsonPrimitive
import me.nathanfallet.teamcode.cli.platform.ServicePlatform
import okio.Path.Companion.toPath
import kotlin.io.encoding.Base64

/** Codex merges files/layers, but repeated CLI assignments replace arrays within that layer. */
internal fun hasCodexHookOverrides(arguments: List<String>): Boolean {
    val options = arguments.takeWhile { it != "--" }
    return options.indices.any { index ->
        val argument = options[index]
        val value = when {
            argument == "-c" || argument == "--config" -> options.getOrNull(index + 1)
            argument.startsWith("--config=") -> argument.removePrefix("--config=")
            argument.startsWith("-c") -> argument.removePrefix("-c").removePrefix("=")
            else -> null
        }
        val key = value?.substringBefore('=')?.filterNot { it.isWhitespace() || it == '"' || it == '\'' }
        key == "hooks" || key?.startsWith("hooks.") == true
    }
}

/** A stable hook definition avoids re-review on every ephemeral proxy port or token rotation. */
internal fun codexGuidanceOptions(platform: ServicePlatform, config: String): List<String> {
    require(platform.executable.toPath().isAbsolute && config.toPath().isAbsolute)
    val arguments = listOf(platform.executable, "agents", "hook", "--config", config)
    require(arguments.none { '\u0000' in it })
    val command = if (platform.os == "windows") {
        // Explicit PowerShell with an encoded script avoids cmd.exe expansion and preserves Unicode paths.
        val script = "[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(\$false); & " +
            arguments.joinToString(" ") { "'${it.replace("'", "''")}'" }
        val utf16 = ByteArray(script.length * 2)
        script.forEachIndexed { index, character ->
            utf16[index * 2] = character.code.toByte()
            utf16[index * 2 + 1] = (character.code ushr 8).toByte()
        }
        "powershell.exe -NoLogo -NoProfile -NonInteractive -EncodedCommand ${Base64.encode(utf16)}"
    } else arguments.joinToString(" ") { "'${it.replace("'", "'\"'\"'")}'" }
    val handler = "[{hooks=[{type=\"command\",command=${JsonPrimitive(command)},timeout=5,additionalContextLimit=1000}]}]"
    return listOf("-c", "hooks.UserPromptSubmit=$handler", "-c", "hooks.PreToolUse=$handler")
}
