package me.nathanfallet.teamcode.infrastructure.database

import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.gateway.WireProtocol
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.routing.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class SessionAffinityPersistenceTest {
    private fun hint(conversation: Char = 'b', model: String = "claude-opus-test", route: Char = 'c') =
        RequestAffinity("a".repeat(64), conversation.toString().repeat(64), model, route.toString().repeat(64), WireProtocol.MESSAGES)

    @Test fun independentReplicasPreserveAffinityWithoutBypassingEligibilityOrOwnership() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initial = team.bootstrap()!!
                val pending = team.login("admin", initial, "affinity test")
                team.changePassword(pending.principal, initial, "affinity test replacement password")
                val actor = team.login("admin", "affinity test replacement password", "affinity test").principal
                val store = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 23 }))
                suspend fun account(name: String, provider: String = "anthropic", pool: String? = null): AccountRecord {
                    val verified = VerifiedProviderAccount(AccountIdentity("default", provider, name, "test-org"), name)
                    val record = (store.upsert(verified, ProviderCredentials("synthetic-access", "synthetic-refresh", Long.MAX_VALUE), null, "enroll-affinity-$name") as EnrollmentResult.Created).account
                    store.configure("default", record.id, name, false, 0, pool)
                    return store.handover("default", record.id, accessOnly = true)
                }
                val accounts = listOf(account("first"), account("second"))
                val admissionA = SqlAdmissionStore(a)
                val admissionB = SqlAdmissionStore(b)
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4))
                suspend fun place(store: SqlAdmissionStore, affinity: RequestAffinity, model: String = affinity.requestedModel, provider: String = "anthropic"): Reservation =
                    store.reserve(actor, 100, model, provider, affinity = affinity).also { store.settle(it, 0) }

                val parent = hint()
                val first = place(admissionA, parent).account
                val other = accounts.single { it.id != first.id }
                assertEquals(RouteTarget("anthropic", parent.requestedModel), admissionB.preferredTarget(actor, parent))
                // A fresh subagent sharing the session goes elsewhere; later parent turns retain their cache.
                assertEquals(other.id, place(admissionB, hint('d')).account.id)
                assertEquals(first.id, place(SqlAdmissionStore(b), parent).account.id)

                val now = a.transaction { it.nowMillis() }
                admissionA.observe(first.id, listOf(AccountQuota(first.id, "model:sonnet", 1.0, now, now + 3_600_000)))
                assertEquals(other.id, place(admissionB, hint(model = "claude-sonnet-test")).account.id)
                assertEquals(first.id, place(admissionA, parent).account.id, "A Sonnet diversion must not move the Opus pin")

                // Exclusions and cooldowns still rotate a pinned request; no affinity bypasses admission.
                val excluded = admissionB.reserve(actor, 100, parent.requestedModel, excludedAccountIds = setOf(first.id), affinity = parent)
                assertEquals(other.id, excluded.account.id); admissionB.settle(excluded, 0)
                admissionA.coolDown(other.id, now + 60_000)
                assertEquals(first.id, place(admissionA, parent).account.id)
                a.transaction { it.execute("UPDATE accounts SET cooldown_until = 0 WHERE id = ?", other.id) }

                store.configure("default", other.id, other.displayName, false, 5)
                assertEquals(other.id, place(admissionB, parent).account.id, "An administrator's higher priority preempts affinity")
                store.configure("default", other.id, other.displayName, true, 5)
                assertEquals(first.id, place(admissionB, parent).account.id, "Disabled accounts cannot retain a pin")
                store.configure("default", other.id, other.displayName, false, 0)

                // Two independent connection pools see the first placement before admitting the next.
                val concurrent = coroutineScope { listOf(admissionA, admissionB).map { service -> async {
                    service.reserve(actor, 100, parent.requestedModel, affinity = hint('e'))
                } }.awaitAll() }
                assertEquals(1, concurrent.map { it.account.id }.distinct().size)
                val overflow = admissionB.reserve(actor, 100, parent.requestedModel, affinity = hint('e'))
                assertNotEquals(concurrent.first().account.id, overflow.account.id, "A full physical account must not bypass its slot ceiling")
                (concurrent + overflow).forEach { admissionA.settle(it, 0) }

                account("codex", "codex")
                place(admissionB, parent, "gpt-6-astra", "codex")
                assertEquals(RouteTarget("codex", "gpt-6-astra"), admissionA.preferredTarget(actor, parent))
                assertNull(admissionA.preferredTarget(actor, hint(route = 'f')), "Changed routing rules must not inherit an old target")

                val created = team.createMember(actor, "colleague", Role.MEMBER)
                val colleaguePending = team.login("colleague", created.temporaryPassword, "test")
                team.changePassword(colleaguePending.principal, created.temporaryPassword, "colleague new password")
                val colleague = team.login("colleague", "colleague new password", "test").principal
                assertNull(admissionB.preferredTarget(colleague, parent), "A client-supplied session cannot read another member's pin")
                val privateAccount = account("protected", pool = "protected")
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4, "protected"))
                assertEquals(privateAccount.id, place(admissionB, parent).account.id, "Moving a member into a protected pool invalidates old account eligibility")
                admissionA.configure(actor, MemberPolicy(actor.member.id, 0, 4))

                a.transaction { it.execute("UPDATE conversation_affinities SET expires_at = 0, last_seen_at = 0 WHERE team_id = 'default'") }
                assertNull(admissionA.preferredTarget(actor, parent))
                admissionB.maintain()
                assertEquals(0, a.transaction { it.rows("SELECT COUNT(*) FROM conversation_affinities") { row -> row.getInt(1) }.single() })
                place(admissionA, parent)
                // A client minting fresh identifiers cannot grow persistent hints without bound.
                // Fill this member's real default limit, then verify admission continues unpinned.
                a.transaction { c ->
                    val current = c.nowMillis()
                    c.prepareStatement("INSERT INTO conversation_affinities (team_id, member_id, session_key, conversation_key, requested_model, route_key, provider, resolved_model, account_id, last_seen_at, expires_at) VALUES ('default', ?, ?, ?, ?, ?, 'anthropic', ?, ?, ?, ?)").use { statement ->
                        for (index in 1 until SqlSessionAffinity.MAX_MEMBER_HINTS) {
                            statement.setString(1, actor.member.id); statement.setString(2, parent.sessionKey)
                            statement.setString(3, index.toString(16).padStart(64, '0'))
                            statement.setString(4, parent.requestedModel); statement.setString(5, parent.routeKey)
                            statement.setString(6, parent.requestedModel); statement.setString(7, first.id)
                            statement.setLong(8, current); statement.setLong(9, current + 3_600_000)
                            statement.addBatch()
                        }
                        statement.executeBatch()
                    }
                }
                place(admissionB, hint('d'))
                assertNull(admissionA.preferredTarget(actor, hint('d')))
                place(admissionB, parent)
                assertNotNull(admissionA.preferredTarget(actor, parent), "Existing affinity still renews at the storage limit")
                assertEquals(SqlSessionAffinity.MAX_MEMBER_HINTS, a.transaction {
                    it.rows("SELECT COUNT(*) FROM conversation_affinities WHERE member_id = ?", actor.member.id) { row -> row.getInt(1) }.single()
                })
                team.logout(actor)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { admissionB.preferredTarget(actor, parent) }.code)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { place(admissionB, parent) }.code)
            } }
        }
    }
}
