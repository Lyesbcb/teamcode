package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.runBlocking
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.flywaydb.core.Flyway
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class UnknownQuotaResetPersistenceTest {
    @Test fun upgradePreservesKnownQuotasAndReplicasShareUnknownResetFreshness() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password)
                .locations("classpath:db/migration").target("9").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                val team = SqlTeamService(a)
                val temporary = team.bootstrap()!!
                val initial = team.login("admin", temporary, "quota fixture")
                team.changePassword(initial.principal, temporary, "quota fixture replacement password")
                val admin = team.login("admin", "quota fixture replacement password", "quota fixture").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 19 }))
                val account = (store.upsert(
                    VerifiedProviderAccount(AccountIdentity("default", "anthropic", "fixture-user", "fixture-org"), "Fixture account"),
                    ProviderCredentials("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3_600_000), null, "quota-fixture-enrollment"
                ) as EnrollmentResult.Created).account
                store.handover("default", account.id)
                val first = SqlAdmissionStore(a)
                val second = SqlAdmissionStore(b)
                val baseline = first.reserve(admin, 1, "claude-opus")
                second.settle(baseline, 0)
                val now = a.transaction { it.nowMillis() }
                val known = AccountQuota(account.id, "five_hour", .99, now - 5_000, now + 3_600_000)
                first.observe(account.id, listOf(known))
                assertEquals(known, second.quotas(admin).single())
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                a.migrate()
                assertEquals(known, second.quotas(admin).single(), "Nullable migration changed a known observation")

                val zero = known.copy(utilization = 0.0, observedAtMillis = now, resetsAtMillis = null)
                second.observe(account.id, listOf(zero))
                first.observe(account.id, listOf(known.copy(observedAtMillis = now - 1_000)))
                assertEquals(zero, first.quotas(admin).single(), "Delayed known-reset sample replaced a newer unknown-reset observation")
                val admitted = first.reserve(admin, 1, "claude-opus")
                second.settle(admitted, 0)
                assertTrue(a.transaction { c -> c.rows("SELECT resets_at FROM quota_history WHERE account_id = ? ORDER BY observed_at DESC LIMIT 1", account.id) { it.nullableLong("resets_at") }.single() == null })

                val scoped = AccountQuota(account.id, "model:opus", 1.0, now, null)
                first.observe(account.id, listOf(scoped))
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                val sonnet = second.reserve(admin, 1, "claude-sonnet")
                first.settle(sonnet, 0)

                // Simulate observation age in this isolated database; never sleep through a real window.
                a.transaction { c -> c.execute("UPDATE quota_windows SET observed_at = ? WHERE account_id = ? AND window_name = 'model:opus'", now - MAX_QUOTA_OBSERVATION_AGE_MILLIS - 1, account.id) }
                val staleProbe = second.reserve(admin, 1, "claude-opus")
                first.settle(staleProbe, 0)
                assertNull(first.quotas(admin).single { it.window == "model:opus" }.resetsAtMillis)

                // Known future reset protection is retained even when its observation is old.
                a.transaction { c -> c.execute("UPDATE quota_windows SET resets_at = ? WHERE account_id = ? AND window_name = 'model:opus'", now + 3_600_000, account.id) }
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { second.reserve(admin, 1, "claude-opus") }.code)
                val before = second.quotas(admin)
                first.observe(account.id, listOf(scoped.copy(utilization = 0.0, observedAtMillis = now + 3_600_000)))
                assertEquals(before, second.quotas(admin), "Future-dated samples must not erase protection")
            } }
        }
    }
}
