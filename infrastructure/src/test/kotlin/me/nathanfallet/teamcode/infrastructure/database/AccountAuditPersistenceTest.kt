package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class AccountAuditPersistenceTest {
    @Test fun accountWritesRevalidateAfterRemoteVerificationAndAuditOnlyCommittedChanges() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val first = SqlTeamService(a); val second = SqlTeamService(b)
                val initial = first.bootstrap()!!
                val pending = first.login("admin", initial, "Audit fixture")
                first.changePassword(pending.principal, initial, "audit replacement password")
                val login = first.login("admin", "audit replacement password", "Audit fixture")
                val actor = login.principal
                val cipher = CredentialCipher(ByteArray(32) { 61 })
                val store = SqlAccountStore(a, cipher)
                val remoteStore = SqlAccountStore(b, cipher)
                val identity = AccountIdentity("default", "anthropic", "verified-provider-id", "verified-org")
                val verified = VerifiedProviderAccount(identity, "Provider account")
                val credentials = ProviderCredentials("synthetic-access-private", "synthetic-refresh-private", Long.MAX_VALUE)
                val verifier = object : AccountVerifier {
                    override suspend fun verify(teamId: String, credentials: ProviderCredentials) = verified
                }
                val accounts = AccountManagement(store, verifier)
                suspend fun events() = a.query {
                    AuditEvents.selectAll().where { (AuditEvents.teamId eq "default") and (AuditEvents.action like "account.%") }
                        .orderBy(AuditEvents.id).map { Triple(it[AuditEvents.actorId], it[AuditEvents.action], it[AuditEvents.subjectId]) }
                }
                suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                val enrolled = (accounts.enroll(actor, credentials, "enrollment-operation-01") as EnrollmentResult.Created).account
                assertEquals(listOf(Triple(actor.member.id, "account.enrolled", enrolled.id)), events())
                assertIs<EnrollmentResult.Created>(accounts.enroll(actor, credentials, "enrollment-operation-01"))
                assertEquals(1, events().size, "Idempotent retries must not append duplicate audit events")
                assertIs<EnrollmentResult.Conflict>(accounts.enroll(actor, ProviderCredentials("different-secret", "different-refresh", Long.MAX_VALUE), "enrollment-operation-01"))
                assertEquals(1, events().size)
                val updated = (accounts.enroll(actor, credentials, "enrollment-operation-02") as EnrollmentResult.Updated).account
                assertEquals(enrolled.id, updated.id); assertEquals(2, updated.credentialVersion)
                accounts.configure(actor, enrolled.id, "Operator label", true, 5, "protected")
                assertEquals("Operator label", remoteStore.find(identity)!!.displayName)
                accounts.handover(actor, enrolled.id, accessOnly = true)
                accounts.handover(actor, enrolled.id, accessOnly = true)
                accounts.handover(actor, enrolled.id)
                assertEquals(listOf("account.enrolled", "account.credentials_updated", "account.configured", "account.access_only", "account.refresh_handover"), events().map { it.second })
                assertTrue(events().all { it.first == actor.member.id && it.third == enrolled.id })
                val count = events().size
                failure("not_found") { accounts.configure(actor, UUID.randomUUID().toString(), "Missing", false, 0) }
                failure("forbidden") { store.configure(AccountMutationActor.User(actor), "foreign", enrolled.id, "Attack", false, 0) }
                failure("credential_scope") { accounts.enroll(actor.copy(serviceScopes = setOf(ServiceScope.INFERENCE)), credentials, "service-operation-01") }
                failure("authentication_required") { accounts.configure(actor.copy(tokenId = UUID.randomUUID().toString()), enrolled.id, "Forged", false, 0) }
                assertEquals(count, events().size)
                assertEquals("Operator label", remoteStore.find(identity)!!.displayName)

                // A real second pool revokes the session while provider verification is in flight.
                val verifying = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
                val delayedIdentity = identity.copy(accountId = "must-not-be-enrolled")
                val delayed = AccountManagement(store, object : AccountVerifier {
                    override suspend fun verify(teamId: String, credentials: ProviderCredentials): VerifiedProviderAccount {
                        verifying.complete(Unit); release.await()
                        return verified.copy(identity = delayedIdentity)
                    }
                })
                val result = async {
                    try { delayed.enroll(actor, credentials, "revoked-operation-01"); "unexpected_success" }
                    catch (error: TeamFailure) { error.code }
                }
                verifying.await(); second.logout(actor); release.complete(Unit)
                assertEquals("authentication_required", withTimeout(5_000) { result.await() })
                assertNull(remoteStore.find(delayedIdentity))
                failure("authentication_required") { accounts.enroll(actor, credentials, "enrollment-operation-01") }
                failure("authentication_required") { accounts.configure(actor, enrolled.id, "Revoked", false, 0) }
                failure("authentication_required") { accounts.handover(actor, enrolled.id, accessOnly = true) }
                assertEquals(count, events().size)
                val current = second.login("admin", "audit replacement password", "Audit reader").principal
                val journal = SqlAuditService(b).list(current).events
                assertTrue(journal.any { it.action == "member.bootstrap" && it.actorId == "system" && it.subjectId == actor.member.id })
                assertTrue(journal.any { it.action == "session.logout" && it.subjectId == actor.tokenId })
                assertFalse(journal.toString().contains(credentials.accessToken)); assertFalse(journal.toString().contains(credentials.refreshToken))
                val system = (store.upsert(AccountMutationActor.System, verified.copy(identity = delayedIdentity), credentials, null, "system-operation-01") as EnrollmentResult.Created).account
                assertEquals(Triple("system", "account.enrolled", system.id), events().last())
                assertNull(second.bootstrap())
                assertEquals(1, SqlAuditService(a).list(current).events.count { it.action == "member.bootstrap" })
            } }
        }
    }
}
