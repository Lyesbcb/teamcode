package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class QuotaModelLimitsPersistenceTest {
    @Test fun replicasShareModelBindingsAndMonotonicAuthoritativeSnapshots() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate()
                val team = SqlTeamService(a)
                val temporary = team.bootstrap()!!
                val initial = team.login("admin", temporary, "quota binding fixture")
                team.changePassword(initial.principal, temporary, "quota binding replacement password")
                val admin = team.login("admin", "quota binding replacement password", "quota binding fixture").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 21 }))
                suspend fun enroll(name: String, provider: String): AccountRecord {
                    val account = (store.upsert(VerifiedProviderAccount(AccountIdentity("default", provider, name, "fixture-org"), name),
                        ProviderCredentials("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3_600_000), null, name) as EnrollmentResult.Created).account
                    store.handover("default", account.id)
                    return account
                }
                val account = enroll("codex-a", "codex")
                val other = enroll("codex-b", "codex")
                val claude = enroll("claude-a", "anthropic")
                val first = SqlAdmissionStore(a)
                val second = SqlAdmissionStore(b)
                val now = a.query { nowMillis() }
                val start = now - 180_000
                val cap = AccountQuota(account.id, "model:base_model_inference:seven_day", 1.0, start, now + 3_600_000)
                suspend fun admit(model: String) {
                    assertFalse(first.isQuotaExhausted(account.id, model))
                    val reservation = second.reserve(admin, 1, model, "codex", setOf(other.id))
                    assertEquals(account.id, reservation.account.id)
                    first.settle(reservation, 0)
                }
                suspend fun blocked() {
                    assertTrue(first.isQuotaExhausted(account.id, "gpt-reserve"))
                    assertFalse(second.isQuotaExhausted(other.id, "gpt-reserve"))
                    assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "gpt-reserve", "codex", setOf(other.id)) }.code)
                }
                suspend fun mapping(id: String = account.id) = b.query { readQuotaModelLimits("default")[id].orEmpty() }
                first.observe(account.id, listOf(cap))
                admit("gpt-reserve") // No observed alias yet: the unrelated quota name cannot identify this model.
                second.observe(account.id, QuotaUpdate(emptyList(), start + 1, modelLimit = ModelLimitObservation("gpt-reserve", "base_model_inference")))
                blocked()
                admit("gpt-6-luna")
                val rerouted = first.reserve(admin, 1, "gpt-reserve", "codex")
                assertEquals(other.id, rerouted.account.id, "The first account's binding leaked onto another subscription")
                second.settle(rerouted, 0)
                first.observe(account.id, QuotaUpdate(emptyList(), start, modelLimit = ModelLimitObservation("gpt-reserve", "premium")))
                first.observe(account.id, QuotaUpdate(emptyList(), start + 10))
                assertEquals("base_model_inference", mapping()["gpt-reserve"])
                blocked()

                // An explicit complete empty model list removes the old cap, not its audit history.
                second.observe(account.id, QuotaUpdate(emptyList(), start + 20, replaceModelWindows = true))
                admit("gpt-reserve")
                assertTrue(first.quotas(admin).none { it.accountId == account.id })
                assertTrue(a.query { QuotaHistory.selectAll().where { QuotaHistory.accountId eq account.id }.count() } > 0)
                first.observe(account.id, QuotaUpdate(listOf(cap.copy(observedAtMillis = start + 10)), start + 10))
                assertTrue(second.quotas(admin).none { it.accountId == account.id }, "Delayed headers resurrected a retired cap")
                admit("gpt-reserve")

                // A newer header beats an older full probe even if the probe finishes last.
                first.observe(account.id, QuotaUpdate(listOf(cap.copy(observedAtMillis = start + 30)), start + 30))
                second.observe(account.id, QuotaUpdate(emptyList(), start + 25, replaceModelWindows = true))
                blocked()
                first.observe(account.id, QuotaUpdate(emptyList(), start + 40))
                blocked() // Partial observations never mean removal.
                second.observe(account.id, QuotaUpdate(emptyList(), start + 50, replaceModelWindows = true))
                first.observe(account.id, QuotaUpdate(listOf(cap.copy(observedAtMillis = start + 45)), start + 45, replaceModelWindows = true))
                admit("gpt-reserve")
                assertTrue(second.quotas(admin).none { it.accountId == account.id })

                // Concurrent replicas converge regardless of which transaction acquires the lock first.
                coroutineScope {
                    val header = async { first.observe(account.id, QuotaUpdate(listOf(cap.copy(observedAtMillis = start + 70)), start + 70)) }
                    val probe = async { second.observe(account.id, QuotaUpdate(emptyList(), start + 60, replaceModelWindows = true)) }
                    awaitAll(header, probe)
                }
                blocked()
                assertEquals(start + 70, a.query { QuotaWindows.select(QuotaWindows.windowStartedAt).where { QuotaWindows.accountId eq account.id }.single()[QuotaWindows.windowStartedAt] }, "A reintroduced cap must not extrapolate from its retired incarnation")
                first.observe(account.id, QuotaUpdate(emptyList(), now + 3_600_000, replaceModelWindows = true))
                blocked() // Future timestamps cannot advance the removal watermark.

                val claudeCap = cap.copy(accountId = claude.id, window = "model:opus")
                first.observe(claude.id, listOf(claudeCap))
                second.observe(claude.id, QuotaUpdate(emptyList(), start + 80, replaceModelWindows = true, modelLimit = ModelLimitObservation("claude-opus", "premium")))
                assertTrue(mapping(claude.id).isEmpty())
                assertEquals(claudeCap, first.quotas(admin).single { it.accountId == claude.id })

                // Client-selected model names cannot grow the per-account mapping without bound.
                repeat(100) { index -> first.observe(account.id, QuotaUpdate(emptyList(), start + 100 + index, modelLimit = ModelLimitObservation("model-$index", "premium"))) }
                assertEquals(32, mapping().size)
                assertTrue("model-99" in mapping())
                assertFalse("model-0" in mapping())
                assertTrue(mapping(other.id).isEmpty())
                second.observe(account.id, QuotaUpdate(emptyList(), start + 300, modelLimit = ModelLimitObservation("bad\nmodel", "premium")))
                assertEquals(32, mapping().size)
            } }
        }
    }
}
