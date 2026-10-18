package me.nathanfallet.teamcode.cli.accounts

import com.github.ajalt.clikt.command.*
import com.github.ajalt.clikt.core.*
import com.github.ajalt.clikt.parameters.options.*
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.mordant.terminal.Terminal
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import me.nathanfallet.teamcode.api.models.EnrollAccountRequest
import me.nathanfallet.teamcode.cli.loadConfig
import me.nathanfallet.teamcode.cli.sdk
import me.nathanfallet.teamcode.cli.platform.*
import me.nathanfallet.teamcode.client.ClaudeEnrollmentClient
import me.nathanfallet.teamcode.client.TeamCodeException
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path.Companion.toPath

class Accounts : SuspendingNoOpCliktCommand(name = "accounts") {
    init { subcommands(ListAccounts(), AddAccount(), ImportAccounts(), Handover()) }
}
private fun randomCode() = secureRandomBytes(32).toByteString().base64().replace('+', '-').replace('/', '_').trimEnd('=')
private class ListAccounts : SuspendingCliktCommand(name = "list") {
    override suspend fun run() { sdk(loadConfig()).use { client ->
        client.accounts().forEach { echo("${it.id} | ${it.displayName} | ${it.state}${if (it.disabled) " | disabled" else ""}") }
    } }
}
private class AddAccount : SuspendingCliktCommand(name = "add") {
    override fun help(context: Context) = "Sign in to Claude locally, then send verified credentials to your TeamCode server. Existing accounts are updated without replacing their settings."
    override suspend fun run() {
        val config = loadConfig()
        sdk(config).use { client ->
            client.accounts() // Verify administrator access before opening provider sign-in.
            val verifier = randomCode()
            val state = randomCode()
            val challenge = verifier.encodeToByteArray().toByteString().sha256().base64().replace('+', '-').replace('/', '_').trimEnd('=')
            val url = URLBuilder("https://claude.ai/oauth/authorize").apply { parameters.apply {
                append("code", "true"); append("client_id", ClaudeEnrollmentClient.CLIENT_ID)
                append("response_type", "code"); append("redirect_uri", ClaudeEnrollmentClient.REDIRECT_URI)
                append("scope", "org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload")
                append("code_challenge", challenge); append("code_challenge_method", "S256"); append("state", state)
            } }.buildString()
            echo("Open this URL in your browser, sign in, then paste the complete code#state shown by Claude:")
            echo(url)
            val paste = Terminal().readLineOrNull(hideInput = true)?.trim().orEmpty()
            val code = parseLoginCode(paste, state)
            val input = ClaudeEnrollmentClient { configure -> HttpClient(httpEngine()) { configure(this) } }.use { it.exchange(code, state, verifier, randomCode()) }
            val result = client.enrollAccount(input)
            echo("${if (result.created) "Added" else "Updated"} ${result.account.displayName} (${result.account.id}).")
            echo("Stop any other refresher for this account, then run: teamcode accounts handover ${result.account.id} --confirm")
        }
    }
}
internal fun parseLoginCode(paste: String, state: String): String {
    require(paste.length in 1..8192) { "Invalid authorization code." }
    val parts = paste.split('#', limit = 2)
    require(parts.size == 2 && parts[0].isNotBlank() && parts[1] == state) { "OAuth state mismatch. Start a new sign-in attempt." }
    return parts[0]
}
private class ImportAccounts : SuspendingCliktCommand(name = "import") {
    private val from by option("--from").required()
    private val config by option("--config", help = "Explicit TeamClaude configuration file; otherwise use its XDG location.")
    private val dryRun by option("--dry-run", help = "Parse locally without uploading credentials.").flag()
    private val takeOwnership by option("--take-ownership", help = "Confirm other refreshers have stopped and activate imported accounts.").flag()
    override suspend fun run() {
        require(from == "teamclaude") { "Only --from teamclaude is supported." }
        val path = (config ?: sourceConfig()).toPath()
        val info = FileSystem.SYSTEM.metadata(path)
        require(info.isRegularFile && (info.size ?: Long.MAX_VALUE) <= MAX_IMPORT_BYTES)
        val source = FileSystem.SYSTEM.read(path) { readUtf8((MAX_IMPORT_BYTES + 1L).coerceAtMost(info.size!!)) }
        val entries = parseTeamClaudeConfig(source)
        if (dryRun) {
            entries.forEach { echo("${it.label}: ${if (it is ImportEntry.Ready) "ready for server verification" else (it as ImportEntry.Rejected).reason}") }
            return
        }
        var failed = 0
        sdk(loadConfig()).use { client ->
            client.accounts()
            entries.forEach { entry ->
                if (entry is ImportEntry.Rejected) { failed++; echo("Skipped ${entry.label}: ${entry.reason}"); return@forEach }
                entry as ImportEntry.Ready
                try {
                    val credentials = entry.credentials
                    val result = client.enrollAccount(EnrollAccountRequest(credentials.accessToken, credentials.refreshToken, credentials.expiresAtMillis, randomCode()))
                    if (takeOwnership) client.handoverAccount(result.account.id)
                    echo("${if (result.created) "Added" else "Updated"} ${result.account.displayName} (${result.account.id}).")
                } catch (error: TeamCodeException) { failed++; echo("Failed ${entry.label}: server returned HTTP ${error.statusCode}. Reconnect expired accounts with accounts add.") }
            }
        }
        echo("Import complete: ${entries.size - failed} succeeded, $failed skipped or failed. Source configuration was not modified.")
        if (!takeOwnership) echo("Stop the source refresher, then activate each account with accounts handover <id> --confirm.")
        check(failed == 0) { "Some accounts were not imported." }
    }
    private fun sourceConfig(): String {
        environment("TEAMROUTER_CONFIG")?.let { return it }
        environment("TEAMCLAUDE_CONFIG")?.let { return it }
        val base = environment("XDG_CONFIG_HOME") ?: "${environment("HOME") ?: environment("USERPROFILE") ?: error("No home directory.")}/.config"
        return listOf("$base/teamrouter.json", "$base/teamclaude.json").firstOrNull { FileSystem.SYSTEM.exists(it.toPath()) } ?: error("No TeamClaude configuration found. Use --config.")
    }
}
private class Handover : SuspendingCliktCommand(name = "handover") {
    private val id by argument()
    private val confirm by option("--confirm", help = "Confirm no other process will refresh this account's tokens.").flag()
    override suspend fun run() {
        require(confirm) { "Stop other refreshers first, then pass --confirm." }
        sdk(loadConfig()).use { echo("Account ${it.handoverAccount(id).id} is now owned by TeamCode.") }
    }
}
