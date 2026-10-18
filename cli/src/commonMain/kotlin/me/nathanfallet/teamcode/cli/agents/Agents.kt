package me.nathanfallet.teamcode.cli.agents

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.api.Serialization
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import kotlin.time.Clock

class Agents : SuspendingNoOpCliktCommand(name = "agents") {
    init { subcommands(Watch(), Hint()) }
}
private class Watch : SuspendingCliktCommand(name = "watch") {
    override fun help(context: Context) = "Print fresh guidance updates as JSON lines. This never launches or terminates tasks."
    override suspend fun run() {
        sdk(loadConfig()).use { client ->
            var previous: String? = null
            while (currentCoroutineContext().isActive) {
                try {
                    val value = client.directive().directive
                    if (value != null && value.id != previous && value.expiresAtMillis > Clock.System.now().toEpochMilliseconds()) {
                        echo(Serialization.json.encodeToString(value))
                        client.acknowledgeDirective(value.id, "RECEIVED_BY_CLI")
                        previous = value.id
                    }
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { echo("Guidance is temporarily unavailable; retrying.", err = true) }
                delay(15_000)
            }
        }
    }
}
private class Hint : SuspendingCliktCommand(name = "hint") {
    private val format by option("--format").default("text")
    override fun help(context: Context) = "Emit current guidance for an agent hook. --format claude emits PreToolUse additional context."
    override suspend fun run() {
        require(format in setOf("text", "claude", "json"))
        // A guidance outage must not block an agent hook; server-side admission remains authoritative.
        try {
            sdk(loadConfig()).use { client ->
                val value = client.directive().directive ?: return@use
                if (value.expiresAtMillis <= Clock.System.now().toEpochMilliseconds()) return@use
                when (format) {
                    "json" -> echo(Serialization.json.encodeToString(value))
                    "claude" -> echo(buildJsonObject { putJsonObject("hookSpecificOutput") { put("hookEventName", "PreToolUse"); put("additionalContext", "TeamCode capacity guidance: ${value.message}") } }.toString())
                    else -> echo(value.message)
                }
                client.acknowledgeDirective(value.id, "EMITTED_TO_HOOK")
            }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) { /* No raw errors or secrets in model context. */ }
    }
}
