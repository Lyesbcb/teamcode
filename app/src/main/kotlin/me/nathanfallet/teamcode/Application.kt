package me.nathanfallet.teamcode

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import java.util.Base64
import me.nathanfallet.teamcode.domain.accounts.AccountManagement
import me.nathanfallet.teamcode.infrastructure.database.SqlAdmissionStore
import me.nathanfallet.teamcode.infrastructure.database.SqlDirectiveService
import me.nathanfallet.teamcode.infrastructure.database.SqlModelPolicyStore
import me.nathanfallet.teamcode.infrastructure.database.SqlCredentialSource
import me.nathanfallet.teamcode.infrastructure.gateway.ClaudePoolGateway
import me.nathanfallet.teamcode.infrastructure.gateway.QuotaMonitor
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.database.SqlAccountStore
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeAccountVerifier
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.domain.gateway.GatewayService
import me.nathanfallet.teamcode.domain.team.TeamService
import me.nathanfallet.teamcode.infrastructure.database.Database
import me.nathanfallet.teamcode.infrastructure.database.SqlTeamService
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.presentation.configureGateway
import org.koin.dsl.koinApplication
import org.koin.dsl.module

fun main() {
    val mode = System.getenv("TEAMCODE_MODE") ?: "server"
    require(mode in setOf("server", "fixture")) { "TEAMCODE_MODE must be server or fixture." }
    val host = System.getenv("TEAMCODE_HOST") ?: "127.0.0.1"
    val port = (System.getenv("TEAMCODE_PORT") ?: "8080").toInt()
    val origin = System.getenv("TEAMCODE_PUBLIC_URL") ?: "http://localhost:$port"
    val database = if (mode == "server") Database(
        requireNotNull(System.getenv("TEAMCODE_DATABASE_URL")) { "Set TEAMCODE_DATABASE_URL (a MySQL JDBC URL)." },
        requireNotNull(System.getenv("TEAMCODE_DATABASE_USER")) { "Set TEAMCODE_DATABASE_USER." },
        requireNotNull(System.getenv("TEAMCODE_DATABASE_PASSWORD")) { "Set TEAMCODE_DATABASE_PASSWORD." },
    ).also { it.migrate() } else null
    val providerHttp = HttpClient(CIO) { followRedirects = false; install(HttpTimeout) { connectTimeoutMillis = 10_000; requestTimeoutMillis = 30_000 } }
    val cipher = database?.let { CredentialCipher(Base64.getDecoder().decode(requireNotNull(System.getenv("TEAMCODE_ENCRYPTION_KEY")) { "Set TEAMCODE_ENCRYPTION_KEY to a base64-encoded 32-byte key." })) }
    val accountStore = database?.let { SqlAccountStore(it, cipher!!) }
    val accounts = accountStore?.let { AccountManagement(it, ClaudeAccountVerifier(providerHttp)) }
    val admission = database?.let { SqlAdmissionStore(it) }
    val modelPolicies = database?.let { SqlModelPolicyStore(it) }
    val directives = database?.let { SqlDirectiveService(it) }
    val transport = ClaudeTransport(providerHttp)
    val credentialSource = database?.let { SqlCredentialSource(it, cipher!!, transport::refresh) }
    val gateway = if (database != null) ClaudePoolGateway(admission!!, credentialSource!!, transport, modelPolicies!!) else FixtureGatewayService()
    val workers = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    if (accountStore != null) {
        val monitor = QuotaMonitor(accountStore, credentialSource!!, admission!!, transport)
        workers.launch {
            while (isActive) {
                try { monitor.sample(); admission.maintain(); directives!!.update() }
                catch (error: CancellationException) { throw error }
                catch (_: Exception) { System.err.println("TeamCode quota sampling is temporarily unavailable.") }
                delay(60_000)
            }
        }
    }
    val team = database?.let { SqlTeamService(it) }
    val initialPassword = runBlocking { team?.bootstrap() }
    if (initialPassword != null) {
        // The sole logging exception: explicitly requested, only after first-admin creation commits.
        System.err.println("TeamCode first-run administrator: admin")
        System.err.println("TeamCode temporary administrator password: $initialPassword")
        System.err.println("Sign in at $origin/dashboard and change this password. It will not be printed again.")
    }
    val token = if (mode == "fixture") requireNotNull(System.getenv("TEAMCODE_ACCESS_TOKEN")) { "Set TEAMCODE_ACCESS_TOKEN for fixture mode." } else ""
    val koin = koinApplication { modules(module {
        single<GatewayService> { gateway }
        if (team != null) single<TeamService> { team }
    }) }
    try {
        embeddedServer(Netty, host = host, port = port) { configureGateway(koin.koin.get(), token, team, origin, accounts, admission, directives, modelPolicies) }.start(wait = true)
    } finally { runBlocking { workers.coroutineContext[Job]?.cancelAndJoin() }; koin.close(); providerHttp.close(); database?.close() }
}
