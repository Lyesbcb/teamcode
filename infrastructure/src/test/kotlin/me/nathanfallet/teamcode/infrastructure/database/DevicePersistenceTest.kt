package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.*
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class DevicePersistenceTest {
    @Test fun devicesRemainScopedRevocableAndBudgetNeutralAcrossReplicasAndUpgrade() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Flyway.configure().dataSource(container.jdbcUrl, container.username, container.password).target("20261019110417").load().migrate()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                fun id() = UUID.randomUUID().toString()
                val password = "device administrator password"
                val legacyLogin = seedLegacyAdministrator(a, password)
                val adminId = legacyLogin.principal.member.id; val legacyId = legacyLogin.principal.tokenId
                val legacyToken = legacyLogin.token
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a); val teamB = SqlTeamService(b)
                val first = SqlDeviceService(a); val second = SqlDeviceService(b)
                val admin = teamB.authenticate(legacyToken)!!
                val legacy = second.sessions(admin).single()
                assertEquals(legacyId, legacy.id); assertNull(legacy.deviceId); assertNull(legacy.createdAt)
                assertTrue(legacy.current); assertTrue(first.devices(admin).isEmpty())
                val installation = id()
                val logins = coroutineScope { listOf(teamA, teamB).map { service -> async { service.login("admin", password, "Developer Mac", installation) } }.awaitAll() }
                val device = first.devices(admin).single()
                assertEquals(2, device.activeSessions)
                assertTrue(second.devices(logins.first().principal).single().current)
                assertEquals(setOf(device.id), second.sessions(admin).filter { it.deviceId != null }.map { it.deviceId }.toSet())
                second.rename(admin, device.id, "Developer workstation")
                val relogin = teamB.login("admin", password, "Untrusted replacement label", installation)
                assertEquals("Developer workstation", first.devices(admin).single().name)
                second.revokeSession(admin, logins.first().principal.tokenId)
                assertNull(teamA.authenticate(logins.first().token))
                assertNotNull(teamA.authenticate(logins.last().token))
                val other = teamA.login("admin", password, "Support Windows", id())
                val otherDevice = second.devices(other.principal).single { it.current }

                val newMember = teamA.createMember(admin, "colleague", Role.MEMBER)
                val pending = teamB.login("colleague", newMember.temporaryPassword, "Colleague Mac", installation)
                teamA.changePassword(pending.principal, newMember.temporaryPassword, "colleague replacement password")
                val colleague = teamB.login("colleague", "colleague replacement password", "Colleague Mac", installation)
                val colleagueDevice = first.devices(colleague.principal).single()
                assertNotEquals(device.id, colleagueDevice.id, "An installation ID is scoped to its authenticated member")
                assertEquals(1, colleagueDevice.activeSessions)
                suspend fun failure(code: String, block: suspend () -> Unit) = assertEquals(code, assertFailsWith<TeamFailure> { block() }.code)
                failure("forbidden") { second.devices(colleague.principal, adminId) }
                failure("forbidden") { first.sessions(colleague.principal, adminId) }
                failure("not_found") { first.rename(colleague.principal, device.id, "attack") }
                failure("not_found") { second.revokeDevice(colleague.principal, device.id) }
                failure("not_found") { first.revokeSession(colleague.principal, relogin.principal.tokenId) }
                failure("authentication_required") { first.devices(colleague.principal.copy(member = colleague.principal.member.copy(role = Role.ADMIN)), adminId) }
                failure("invalid_installation") { teamA.login("admin", password, "Invalid", "bad/id") }
                failure("invalid_device_name") { first.rename(admin, device.id, "bad\nname") }
                assertEquals(colleagueDevice.id, second.devices(admin, colleague.principal.member.id).single().id)
                a.query {
                    val foreignMember = id()
                    Teams.insert { it[Teams.id] = "foreign"; it[name] = "Other team" }
                    Members.insert { it[Members.id] = foreignMember; it[teamId] = "foreign"; it[username] = "other"; it[passwordHash] = "unused-fixture"; it[role] = "MEMBER" }
                    Devices.insert { it[Devices.id] = "00000000-0000-0000-0000-000000000001"; it[memberId] = foreignMember; it[installationId] = installation; it[name] = "Private"; it[createdAt] = 1; it[lastLoginAt] = 1 }
                }
                failure("not_found") { first.revokeDevice(admin, "00000000-0000-0000-0000-000000000001") }

                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 53 }))
                val account = accounts.upsert(VerifiedProviderAccount(AccountIdentity("default", "anthropic", "device-test", "org"), "Device test"), ProviderCredentials("synthetic", "synthetic-refresh", Long.MAX_VALUE), null, "device-enrollment") as EnrollmentResult.Created
                accounts.handover("default", account.account.id, accessOnly = true)
                val admissionA = SqlAdmissionStore(a); val admissionB = SqlAdmissionStore(b)
                admissionA.configure(admin, MemberPolicy(adminId, dailyUnits = 100, maxConcurrent = 2))
                val reservation = admissionA.reserve(relogin.principal, 60, "claude-test")
                failure("member_allowance") { admissionB.reserve(other.principal, 60, "claude-test") }
                admissionA.settle(reservation, 30)

                val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
                val cancellation = RequestCancellationService(SqlRequestCancellationStore(a))
                val legacyGateway = CancellableGateway(object : GatewayService {
                    override suspend fun status() = GatewaySnapshot("test", 0, 0)
                    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
                        assertNull(request.clientRequestId)
                        try { entered.complete(Unit); awaitCancellation() } finally { released.complete(Unit) }
                    }
                }, cancellation)
                val activeFailure = async {
                    try { legacyGateway.forward(GenerationRequest(byteArrayOf(), emptyMap(), relogin.principal)) { _, _ -> }; null }
                    catch (error: TeamFailure) { error.code }
                }
                entered.await()
                second.revokeDevice(other.principal, device.id)
                withTimeout(3_000) { released.await(); assertEquals("authentication_required", activeFailure.await()) }
                for (login in logins + relogin) assertNull(teamB.authenticate(login.token))
                assertNotNull(teamA.authenticate(other.token))
                assertEquals(30, admissionB.capacity(other.principal).chargedAndReservedUnits)
                assertEquals(listOf(otherDevice.id), second.devices(admin).map { it.id })
                val connected = teamA.login("admin", password, "Reconnected Mac", installation)
                val registered = first.devices(connected.principal).single { it.current }
                assertNotEquals(device.id, registered.id)
                assertEquals(30, admissionA.capacity(connected.principal).chargedAndReservedUnits)
                teamB.logout(connected.principal)
                val afterLogout = teamB.login("admin", password, "Ignored label", installation)
                assertEquals(registered.id, second.devices(afterLogout.principal).single { it.current }.id)
                assertEquals("Reconnected Mac", first.devices(admin).single { it.id == registered.id }.name)

                a.query {
                    Tokens.update({ Tokens.id eq afterLogout.principal.tokenId }) { it[expiresAt] = 0 }
                    val existing = Devices.select(Devices.id).where { Devices.memberId eq adminId }.count()
                    val now = nowMillis()
                    Devices.batchInsert((1..(DeviceService.DEVICE_LIMIT - existing).toInt()).toList()) {
                        this[Devices.id] = id(); this[Devices.memberId] = adminId; this[Devices.installationId] = id()
                        this[Devices.name] = "Unused fixture"; this[Devices.createdAt] = now; this[Devices.lastLoginAt] = now
                    }
                }
                assertNull(teamA.authenticate(afterLogout.token))
                assertFalse(second.sessions(admin).any { it.id == afterLogout.principal.tokenId })
                assertEquals(0, first.devices(admin).single { it.id == registered.id }.activeSessions)
                failure("device_limit") { teamB.login("admin", password, "One too many", id()) }
                assertNotNull(teamA.login("admin", password, "Existing still works", installation))
                second.revokeDevice(admin, registered.id)
                assertNotNull(teamB.login("admin", password, "Slot available", id()))
                a.query {
                    val actions = AuditEvents.select(AuditEvents.action).where { AuditEvents.teamId eq "default" }.map { it[AuditEvents.action] }.toSet()
                    assertTrue(actions.containsAll(setOf("device.registered", "device.renamed", "device.revoked", "session.revoked")))
                    assertEquals(0, (Tokens leftJoin Devices).select(Tokens.id).where { Tokens.deviceId.isNotNull() and Devices.id.isNull() }.count())
                }
                second.revokeSession(admin, legacyId)
                assertNull(teamA.authenticate(legacyToken))
            } }
        }
    }
}
