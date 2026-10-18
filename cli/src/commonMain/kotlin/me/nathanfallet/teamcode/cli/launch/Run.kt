package me.nathanfallet.teamcode.cli.launch

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.arguments.*
import me.nathanfallet.teamcode.cli.SystemExit
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.cli.proxy.proxyServer
import me.nathanfallet.teamcode.cli.config.configPath
import me.nathanfallet.teamcode.cli.service.loadService
import me.nathanfallet.teamcode.cli.service.serviceResponds

class Run : SuspendingNoOpCliktCommand(name = "run") {
    override fun help(context: Context) = "Run Claude Code or Codex through an automatically managed local proxy. Pass agent arguments after --."
    init { subcommands(LaunchAgent("claude"), LaunchAgent("codex")) }
}

private class LaunchAgent(private val agent: String) : SuspendingCliktCommand(name = agent) {
    private val arguments by argument("AGENT_ARGS").multiple()
    override suspend fun run() {
        val config = loadConfig()
        val service = loadService(configPath())
        if (service != null && serviceResponds(service, config.serverUrl)) {
            sdk(config).use { it.capabilities() }
            val plan = agentLaunch(agent, arguments, service.port, service.localToken)
            echo("Running $agent through the TeamCode user service.", err = true)
            val code = runInteractiveProcess(agent, plan.arguments, plan.environment)
            if (code != 0) SystemExit.exit(code)
            return
        }
        val client = sdk(config)
        val token = secureRandomBytes(32).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        // A dedicated ephemeral listener avoids collisions between concurrent launches or a user service.
        val server = proxyServer(client, token, 0) { echo(it, err = true) }
        val code = try {
            client.capabilities() // Fail before launching the agent when the saved session is invalid.
            server.start(wait = false)
            val port = server.engine.resolvedConnectors().single().port
            val plan = agentLaunch(agent, arguments, port, token)
            echo("Running $agent through TeamCode.", err = true)
            runInteractiveProcess(agent, plan.arguments, plan.environment)
        } finally {
            server.stop(500, 5_000)
            client.close()
        }
        if (code != 0) SystemExit.exit(code)
    }
}

/** Null values remove inherited provider credentials without changing the parent environment or config files. */
internal class AgentLaunch(val arguments: List<String>, val environment: Map<String, String?>)
internal fun agentLaunch(agent: String, arguments: List<String>, port: Int, token: String): AgentLaunch {
    require(agent in setOf("claude", "codex") && port in 1..65535 && token.length >= 32)
    val origin = "http://127.0.0.1:$port"
    return if (agent == "claude") AgentLaunch(arguments, mapOf(
        "ANTHROPIC_BASE_URL" to origin,
        "ANTHROPIC_API_KEY" to token,
        "ANTHROPIC_AUTH_TOKEN" to null,
        "CLAUDE_CODE_OAUTH_TOKEN" to null,
        "CLAUDE_CODE_USE_BEDROCK" to null,
        "CLAUDE_CODE_USE_VERTEX" to null,
        "CLAUDE_CODE_USE_FOUNDRY" to null,
    )) else {
        val overrides = listOf(
        "-c", "model_provider=\"teamcode\"",
        "-c", "model_providers.teamcode={name=\"TeamCode\",base_url=\"$origin/v1\",env_key=\"TEAMCODE_LOCAL_TOKEN\",wire_api=\"responses\",request_max_retries=0,stream_max_retries=0}",
        )
        // Put overrides in the leaf command scope (exec/resume/review), before any literal delimiter.
        // Root overrides can be discarded by exec --ignore-user-config in some Codex versions.
        val delimiter = arguments.indexOf("--").let { if (it < 0) arguments.size else it }
        AgentLaunch(arguments.take(delimiter) + overrides + arguments.drop(delimiter),
            mapOf("TEAMCODE_LOCAL_TOKEN" to token))
    }
}
