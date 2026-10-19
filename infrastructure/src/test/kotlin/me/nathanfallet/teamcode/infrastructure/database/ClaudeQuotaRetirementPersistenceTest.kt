package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class ClaudeQuotaRetirementPersistenceTest {
    @Test fun replicasRetireOnlyConfirmedCapsAndNeverResurrectThemFromLateResponses() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate()
                val team = SqlTeamService(a)
                val password = team.bootstrap()!!
                val initial = team.login("admin", password, "Claude quota fixture")
                team.changePassword(initial.principal, password, "Claude quota fixture replacement")
                val actor = team.login("admin", "Claude quota fixture replacement", "Claude quota fixture").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 25 }))
                suspend fun account(name: String): AccountRecord {
                    val enrolled = (store.upsert(VerifiedProviderAccount(AccountIdentity("default", "anthropic", name, "org"), name),
                        ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, name) as EnrollmentResult.Created).account
                    return store.handover("default", enrolled.id, accessOnly = true)
                }
                val claude = account("claude-a")
                val other = account("claude-b")
                val first = SqlAdmissionStore(a)
                val second = SqlAdmissionStore(b)
                val now = a.query { nowMillis() }
                val start = now - 180_000
                val cap = AccountQuota(claude.id, "model:opus", 1.0, start, now + 3_600_000)
                fun snapshot(payload: String, at: Long) = ClaudeTransport.usageSnapshot(claude.id, Json.parseToJsonElement(payload).jsonObject, at)
                suspend fun admit(model: String = "claude-opus-test") {
                    val reservation = second.reserve(actor, 1, model, excludedAccountIds = setOf(other.id))
                    assertEquals(claude.id, reservation.account.id)
                    first.settle(reservation, 0)
                }
                suspend fun blocked() {
                    assertTrue(second.isQuotaExhausted(claude.id, "claude-opus-test"))
                    assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(actor, 1, "claude-opus-test", excludedAccountIds = setOf(other.id)) }.code)
                }
                first.observe(claude.id, listOf(cap, cap.copy(window = "seven_day", utilization = .1)))
                first.observe(other.id, listOf(cap.copy(accountId = other.id)))
                blocked()
                admit("claude-sonnet-test")
                for (payload in listOf("{}", """{"seven_day_opus":null}""", """{"limits":[{}]}""")) {
                    second.observe(claude.id, snapshot(payload, start + 10))
                    blocked()
                }
                second.observe(claude.id, snapshot("""{"limits":[],"seven_day_opus":{"utilization":100}}""", start + 20))
                blocked() // The legacy reading still exists, despite the empty modern list.
                second.observe(claude.id, snapshot("""{"limits":[]}""", start + 30))
                admit()
                assertTrue(second.isQuotaExhausted(other.id, "claude-opus-test"), "Retirement must remain account-local")
                assertEquals(listOf("seven_day"), first.quotas(actor).filter { it.accountId == claude.id }.map { it.window })
                assertTrue(a.query { QuotaHistory.selectAll().where { (QuotaHistory.accountId eq claude.id) and (QuotaHistory.windowName eq "model:opus") }.count() } > 0)
                first.observe(claude.id, listOf(cap.copy(observedAtMillis = start + 25)))
                first.observe(claude.id, snapshot("""{"limits":[],"seven_day_opus":{"utilization":100}}""", start + 30))
                admit() // Equal-time or late responses cannot resurrect retired state.

                coroutineScope {
                    awaitAll(
                        async { first.observe(claude.id, listOf(cap.copy(observedAtMillis = start + 50))) },
                        async { second.observe(claude.id, snapshot("""{"limits":[]}""", start + 40)) },
                    )
                }
                blocked() // Newer inference wins even when an older complete probe finishes last.
                val windowStart = b.query { QuotaWindows.select(QuotaWindows.windowStartedAt).where { (QuotaWindows.accountId eq claude.id) and (QuotaWindows.windowName eq "model:opus") }.single()[QuotaWindows.windowStartedAt] }
                assertEquals(start + 50, windowStart)
                second.observe(claude.id, snapshot("""{"limits":[]}""", start + 60))
                admit()
                first.observe(claude.id, listOf(cap.copy(window = "seven_day", observedAtMillis = start + 70)))
                second.observe(claude.id, snapshot("""{"limits":[]}""", start + 80))
                blocked() // Removing all model caps cannot clear shared exhaustion.
                assertTrue(second.isQuotaExhausted(claude.id, "claude-sonnet-test"))
            } }
        }
    }
}
