package me.nathanfallet.teamcode.infrastructure.database

import me.nathanfallet.teamcode.infrastructure.database.tables.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class TeamPersistenceTest {
    @Test fun bootstrapSessionsRevocationAndLastAdministratorSurviveRestarts() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { database ->
                database.migrate()
                val service = SqlTeamService(database)
                val bootstraps = coroutineScope { (1..3).map { async { service.bootstrap() } }.awaitAll() }
                val temporary = bootstraps.filterNotNull().single()
                val initial = service.login("admin", temporary, "test")
                assertTrue(initial.principal.member.passwordChangeRequired)
                assertFailsWith<TeamFailure> { service.members(initial.principal) }
                service.changePassword(initial.principal, temporary, "a secure replacement password")
                assertNull(service.authenticate(initial.token))
                val admin = service.login("admin", "a secure replacement password", "test")
                val created = service.createMember(admin.principal, "alice", Role.MEMBER)
                val alice = service.login("alice", created.temporaryPassword, "test")
                service.changePassword(alice.principal, created.temporaryPassword, "alice has a longer password")
                val member = service.login("alice", "alice has a longer password", "test")
                assertFailsWith<TeamFailure> { service.createMember(member.principal, "mallory", Role.ADMIN) }
                assertFailsWith<TeamFailure> { service.updateMember(admin.principal, admin.principal.member.id, Role.MEMBER, false) }
                assertFailsWith<TeamFailure> { service.updateMember(admin.principal, admin.principal.member.id, Role.ADMIN, true) }
                service.updateMember(admin.principal, created.member.id, Role.MEMBER, true)
                assertNull(service.authenticate(member.token))
                service.updateMember(admin.principal, created.member.id, Role.MEMBER, false)
                val reset = service.resetPassword(admin.principal, created.member.id)
                assertFailsWith<TeamFailure> { service.login("alice", "alice has a longer password", "test") }
                assertTrue(service.login("alice", reset.temporaryPassword, "test").principal.member.passwordChangeRequired)
                val store = SqlAccountStore(database, CredentialCipher(ByteArray(32) { 42 }))
                val verified = VerifiedProviderAccount(AccountIdentity("default", "anthropic", "verified-account", "verified-org"), "Provider name")
                val credentials = ProviderCredentials("synthetic-access", "synthetic-refresh", System.currentTimeMillis() + 3600000)
                val enrolled = store.upsert(AccountMutationActor.System, verified, credentials, null, "operation-00000001") as EnrollmentResult.Created
                val repeat = store.upsert(AccountMutationActor.System, verified, credentials, null, "operation-00000001") as EnrollmentResult.Created
                assertEquals(enrolled.account.id, repeat.account.id)
                val configured = store.configure(AccountMutationActor.System, "default", enrolled.account.id, "Team-owned name", true, 7)
                store.handover(AccountMutationActor.System, "default", configured.id)
                val rotated = ProviderCredentials("synthetic-access-next", "synthetic-refresh-next", credentials.expiresAtMillis)
                val raced = coroutineScope { (2..3).map { n -> async { store.upsert(AccountMutationActor.System, verified, rotated, 1, "operation-0000000$n") } }.awaitAll() }
                assertEquals(1, raced.count { it is EnrollmentResult.Updated })
                assertEquals(1, raced.count { it is EnrollmentResult.Conflict })
                val updated = store.find(verified.identity)!!
                assertEquals(configured.id, updated.id)
                assertEquals("Team-owned name", updated.displayName)
                assertTrue(updated.disabled)
                assertEquals(7, updated.priority)
                assertEquals(AccountState.READY, updated.state)
                assertEquals(2, updated.credentialVersion)
                assertEquals(rotated.refreshToken, store.credentials("default", updated.id).refreshToken)
                assertTrue(store.list("another-team").isEmpty())
                assertFailsWith<TeamFailure> { store.credentials("another-team", updated.id) }
                database.query {
                    val c = this
                    assertTrue(Accounts.select(Accounts.credentialEnvelope).map { it[Accounts.credentialEnvelope] }.all { it.startsWith("v1:") && !it.contains("synthetic") })
                }
                store.configure(AccountMutationActor.System, "default", updated.id, "Shared", false, 0)
                val privateVerified = VerifiedProviderAccount(verified.identity.copy(accountId = "private-account"), "Protected")
                val protected = (store.upsert(AccountMutationActor.System, privateVerified, credentials, null, "operation-private01") as EnrollmentResult.Created).account
                store.configure(AccountMutationActor.System, "default", protected.id, "Protected", false, 0, "alice-only")
                store.handover(AccountMutationActor.System, "default", protected.id)
                val pendingAlice = service.login("alice", reset.temporaryPassword, "test")
                service.changePassword(pendingAlice.principal, reset.temporaryPassword, "alice new secure password")
                val readyAlice = service.login("alice", "alice new secure password", "test").principal
                val admission = SqlAdmissionStore(database)
                admission.configure(admin.principal, MemberPolicy(admin.principal.member.id, 100, 1))
                admission.configure(admin.principal, MemberPolicy(readyAlice.member.id, 100, 1, "alice-only"))
                val contested = coroutineScope { (1..4).map { async { runCatching { admission.reserve(admin.principal, 60, "claude-opus-test") } } }.awaitAll() }
                assertEquals(1, contested.count { it.isSuccess })
                val first = contested.single { it.isSuccess }.getOrThrow()
                assertEquals(updated.id, first.account.id)
                val other = admission.reserve(readyAlice, 60, "claude-opus-test")
                assertEquals(protected.id, other.account.id)
                admission.settle(first, null)
                admission.settle(first, 0) // A duplicate settlement cannot refund uncertain spend.
                assertEquals(60, admission.capacity(admin.principal).chargedAndReservedUnits)
                assertEquals("member_allowance", assertFailsWith<TeamFailure> { admission.reserve(admin.principal, 60, "claude-opus-test") }.code)
                admission.settle(other, 10)
                assertEquals(10, admission.capacity(readyAlice).chargedAndReservedUnits)
                admission.observe(updated.id, listOf(AccountQuota(updated.id, "seven_day", .96, System.currentTimeMillis(), System.currentTimeMillis() + 3_600_000)))
                val codexIdentity = VerifiedProviderAccount(verified.identity.copy(provider = "codex", accountId = "codex-user"), "Codex")
                val codex = (store.upsert(AccountMutationActor.System, codexIdentity, credentials, null, "operation-codex01") as EnrollmentResult.Created).account
                store.handover(AccountMutationActor.System, "default", codex.id, accessOnly = true)
                val codexRequest = admission.reserve(admin.principal, 20, "gpt-6-luna", "codex")
                assertEquals(codex.id, codexRequest.account.id)
                admission.settle(codexRequest, 0)
                assertEquals("pool_unavailable", assertFailsWith<TeamFailure> { admission.reserve(admin.principal, 20, "claude-opus-test") }.code)
                assertEquals(60, SqlAdmissionStore(database).capacity(admin.principal).chargedAndReservedUnits)
                val support = service.createMember(admin.principal, "support", Role.MEMBER)
                admission.configure(admin.principal, MemberPolicy(admin.principal.member.id, 0, 2, null, 5))
                admission.configure(admin.principal, MemberPolicy(readyAlice.member.id, 0, 2, null, 5))
                admission.configure(admin.principal, MemberPolicy(support.member.id, 0, 1, null, 1))
                admission.configurePool(admin.principal, PoolBudget("", 1100))
                assertEquals(500, admission.capacity(admin.principal).dailyAllowanceUnits)
                assertEquals(500, admission.capacity(readyAlice).dailyAllowanceUnits)
                assertEquals(100, admission.capacity(admin.principal, support.member.id).dailyAllowanceUnits)
                assertEquals(60, admission.capacity(admin.principal).chargedAndReservedUnits)
                val expired = ProviderCredentials("expired-access", "rotating-refresh", 1)
                store.upsert(AccountMutationActor.System, verified, expired, 2, "operation-expired01")
                val renewals = java.util.concurrent.atomic.AtomicInteger()
                val refreshing = SqlCredentialSource(database, CredentialCipher(ByteArray(32) { 42 })) { _, _ ->
                    renewals.incrementAndGet(); delay(50)
                    ProviderCredentials("renewed-access", "rotated-refresh", System.currentTimeMillis() + 3_600_000)
                }
                store.handover(AccountMutationActor.System, "default", updated.id, accessOnly = true)
                assertEquals("access_token_expired", assertFailsWith<TeamFailure> { refreshing.fresh(store.find(verified.identity)!!) }.code)
                assertEquals(0, renewals.get())
                assertEquals(AccountState.ACCESS_ONLY, store.find(verified.identity)!!.state)
                store.handover(AccountMutationActor.System, "default", updated.id)
                coroutineScope { (1..4).map { async { refreshing.fresh(store.find(verified.identity)!!) } }.awaitAll() }
                assertEquals(1, renewals.get())
                assertEquals("rotated-refresh", store.credentials("default", updated.id).refreshToken)
                store.upsert(AccountMutationActor.System, privateVerified, expired, 1, "operation-expired02")
                val broken = SqlCredentialSource(database, CredentialCipher(ByteArray(32) { 42 })) { _, _ -> throw IllegalStateException("unknown refresh outcome") }
                assertFailsWith<TeamFailure> { broken.fresh(store.find(privateVerified.identity)!!) }
                assertEquals(AccountState.RECONNECT_REQUIRED, store.find(privateVerified.identity)!!.state)
                // A second service instance uses the same persistent state; migrations are repeatable.
                database.migrate()
                val restarted = SqlTeamService(database)
                assertNull(restarted.bootstrap())
                assertEquals(3, restarted.members(restarted.authenticate(admin.token)!!).size)
                restarted.logout(admin.principal)
                assertNull(service.authenticate(admin.token))
                database.query {
                    val c = this
                    val stored = Members.select(Members.passwordHash).map { it[Members.passwordHash] }
                    assertTrue(stored.all { it.startsWith("pbkdf2-sha256:") && !it.contains(temporary) })
                    assertTrue(Tokens.select(Tokens.tokenHash).map { it[Tokens.tokenHash] }.none { it == admin.token || it == member.token })
                }
            }
        }
    }
}
