package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class TeamPersistenceTest {
    @Test fun bootstrapSessionsRevocationAndLastAdministratorSurviveRestarts() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").use { container ->
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
                val enrolled = store.upsert(verified, credentials, null, "operation-00000001") as EnrollmentResult.Created
                val repeat = store.upsert(verified, credentials, null, "operation-00000001") as EnrollmentResult.Created
                assertEquals(enrolled.account.id, repeat.account.id)
                val configured = store.configure("default", enrolled.account.id, "Team-owned name", true, 7)
                store.handover("default", configured.id)
                val rotated = ProviderCredentials("synthetic-access-next", "synthetic-refresh-next", credentials.expiresAtMillis)
                val raced = coroutineScope { (2..3).map { n -> async { store.upsert(verified, rotated, 1, "operation-0000000$n") } }.awaitAll() }
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
                database.transaction { c ->
                    assertTrue(c.rows("SELECT credential_envelope FROM accounts") { it.getString(1) }.all { it.startsWith("v1:") && !it.contains("synthetic") })
                }
                // A second service instance uses the same persistent state; migrations are repeatable.
                database.migrate()
                val restarted = SqlTeamService(database)
                assertNull(restarted.bootstrap())
                assertEquals(2, restarted.members(restarted.authenticate(admin.token)!!).size)
                restarted.logout(admin.principal)
                assertNull(service.authenticate(admin.token))
                database.transaction { c ->
                    val stored = c.rows("SELECT password_hash FROM members") { it.getString(1) }
                    assertTrue(stored.all { it.startsWith("pbkdf2-sha256:") && !it.contains(temporary) })
                    assertTrue(c.rows("SELECT token_hash FROM tokens") { it.getString(1) }.none { it == admin.token || it == member.token })
                }
            }
        }
    }
}
