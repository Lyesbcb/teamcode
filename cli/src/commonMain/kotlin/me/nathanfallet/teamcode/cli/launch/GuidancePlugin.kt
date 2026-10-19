package me.nathanfallet.teamcode.cli.launch

import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.cli.platform.secureRandomBytes
import me.nathanfallet.teamcode.cli.platform.writePrivateFile
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Session-only plugin; no user/project settings or provider credentials are written. */
internal class GuidancePlugin private constructor(val directory: Path) : AutoCloseable {
    override fun close() { FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false) }
    companion object {
        fun create(configPath: String, port: Int): GuidancePlugin {
            val id = secureRandomBytes(16).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
            val root = requireNotNull(configPath.toPath().parent) / "runs" / id
            val plugin = GuidancePlugin(root)
            try {
                // Create the run directory privately before placing nested plugin files.
                writePrivateFile((root / "owner").toString(), "TeamCode ephemeral guidance plugin\n")
                writePrivateFile((root / ".claude-plugin" / "plugin.json").toString(), """{"name":"teamcode-guidance","version":"1.0.0","description":"Session capacity guidance from your TeamCode server"}""")
                writePrivateFile((root / "hooks" / "hooks.json").toString(), guidanceHooks(port).toString())
                return plugin
            } catch (error: Throwable) { plugin.close(); throw error }
        }
    }
}
internal fun guidanceHooks(port: Int): JsonObject {
    require(port in 1..65535)
    return buildJsonObject { putJsonObject("hooks") {
        for (event in listOf("PreToolUse", "UserPromptSubmit")) putJsonArray(event) { add(buildJsonObject {
            if (event == "PreToolUse") put("matcher", "*")
            putJsonArray("hooks") { add(buildJsonObject {
                put("type", "http"); put("url", "http://127.0.0.1:$port/_teamcode/hooks/claude"); put("timeout", 4)
                putJsonObject("headers") { put("Authorization", "Bearer ${'$'}TEAMCODE_HOOK_TOKEN") }
                putJsonArray("allowedEnvVars") { add(JsonPrimitive("TEAMCODE_HOOK_TOKEN")) }
            }) }
        }) }
    } }
}
