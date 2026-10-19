package me.nathanfallet.teamcode.infrastructure.database

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.tables.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class RequestCancellationPersistenceTest {
    @Test fun cancellationAcrossIndependentPoolsStopsQueuedAndStreamingWorkAndKeepsStorageBounded() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val team = SqlTeamService(a)
                val initialPassword = team.bootstrap()!!
                val initial = team.login("admin", initialPassword, "cancellation test")
                team.changePassword(initial.principal, initialPassword, "cancellation administrator replacement")
                val admin = team.login("admin", "cancellation administrator replacement", "cancellation test").principal
                val created = team.createMember(admin, "colleague", Role.MEMBER)
                val login = team.login("colleague", created.temporaryPassword, "cancellation test")
                team.changePassword(login.principal, created.temporaryPassword, "cancellation colleague replacement")
                val colleague = team.login("colleague", "cancellation colleague replacement", "cancellation test").principal
                val storeA = SqlRequestCancellationStore(a)
                val storeB = SqlRequestCancellationStore(b)
                val serviceA = RequestCancellationService(storeA)
                val serviceB = RequestCancellationService(storeB)
                fun id() = UUID.randomUUID().toString()
                suspend fun count() = a.query { RequestCancellations.selectAll().count() }

                val early = id()
                coroutineScope { listOf(serviceA, serviceB).map { service -> async { service.cancel(admin, early) } }.awaitAll() }
                assertEquals(1, count(), "Concurrent cancellation is idempotent across replicas")
                val expiry = a.query { RequestCancellations.selectAll().single()[RequestCancellations.expiresAt] }
                serviceB.cancel(admin, early)
                assertEquals(expiry, a.query { RequestCancellations.selectAll().single()[RequestCancellations.expiresAt] })
                assertFailsWith<CancellationException> { serviceB.run(admin, early) { fail("Must not dispatch") } }
                assertFalse(storeB.isCancelled(colleague, early), "A marker cannot cancel another member's request")
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { storeB.isCancelled(admin.copy(teamId = "another-team"), early) }.code)

                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 43 }))
                val account = accounts.upsert(AccountMutationActor.System, VerifiedProviderAccount(AccountIdentity("default", "anthropic", "cancellation-test", "org"), "Cancellation account"), ProviderCredentials("synthetic", "synthetic-refresh", Long.MAX_VALUE), null, "cancellation-enrollment") as EnrollmentResult.Created
                accounts.handover(AccountMutationActor.System, "default", account.account.id, accessOnly = true)
                val admissionA = SqlAdmissionStore(a)
                val admissionB = SqlAdmissionStore(b)
                admissionA.configure(admin, MemberPolicy(admin.member.id, maxConcurrent = 1))
                val held = admissionA.reserve(admin, 100, "claude-test")
                val queuedId = id()
                val queued = async { serviceA.run(admin, queuedId) {
                    AwaitAdmissionUseCase(admissionA).reserve(admin, AdmissionInput(100, "claude-test", "anthropic"))
                    fail("The cancelled waiter must never dispatch")
                } }
                withTimeout(5_000) { while (a.query { AdmissionWaiters.selectAll().count() } == 0L) delay(10) }
                serviceB.cancel(admin, queuedId)
                withTimeout(3_000) { assertFailsWith<CancellationException> { queued.await() } }
                assertEquals(0, a.query { AdmissionWaiters.selectAll().count() })
                assertEquals(100, admissionB.capacity(admin).chargedAndReservedUnits, "Only the original held reservation remains")
                admissionB.settle(held, 0)

                val streamId = id()
                val started = CompletableDeferred<Unit>()
                val released = CompletableDeferred<Unit>()
                val survivorReady = CompletableDeferred<Unit>()
                val releaseSurvivor = CompletableDeferred<Unit>()
                val survivorId = id()
                val gateway = CancellableGateway(object : GatewayService {
                    override suspend fun status() = GatewaySnapshot("test", 0, 0)
                    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
                        receive(GenerationHead(200, "text/event-stream"), flow {
                            try {
                                emit("data: started\n\n".toByteArray())
                                if (request.clientRequestId == streamId) { started.complete(Unit); awaitCancellation() }
                                else { survivorReady.complete(Unit); releaseSurvivor.await() }
                            } finally { if (request.clientRequestId == streamId) released.complete(Unit) }
                        })
                    }
                }, serviceA)
                val stream = async { gateway.forward(GenerationRequest(byteArrayOf(), emptyMap(), admin, clientRequestId = streamId)) { _, chunks -> chunks.collect {} } }
                val survivor = async { gateway.forward(GenerationRequest(byteArrayOf(), emptyMap(), colleague, clientRequestId = survivorId)) { _, chunks -> chunks.collect {} } }
                withTimeout(3_000) { started.await(); survivorReady.await() }
                serviceB.cancel(colleague, streamId) // Same UUID under another member must not stop it.
                delay(RequestCancellationService.POLL_MILLIS + 100)
                assertTrue(stream.isActive)
                serviceB.cancel(admin, streamId)
                withTimeout(3_000) { assertFailsWith<CancellationException> { stream.await() }; released.await() }
                assertTrue(survivor.isActive)
                releaseSurvivor.complete(Unit); survivor.await()
                assertEquals(0, admissionA.capacity(admin).chargedAndReservedUnits)

                // Revalidate sessions in the same transaction that inserts the marker.
                team.logout(colleague)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { serviceB.cancel(colleague, id()) }.code)
                assertEquals("authentication_required", assertFailsWith<TeamFailure> { storeB.isCancelled(colleague, survivorId) }.code)
                a.query { RequestCancellations.update { it[expiresAt] = 0 } }
                assertFalse(storeB.isCancelled(admin, early))
                storeB.maintain()
                assertEquals(0, count())

                a.query {
                    val now = nowMillis()
                    RequestCancellations.batchInsert((1..RequestCancellationService.MEMBER_LIMIT.toInt()).toList()) {
                        this[RequestCancellations.teamId] = admin.teamId; this[RequestCancellations.memberId] = admin.member.id
                        this[RequestCancellations.requestId] = id(); this[RequestCancellations.expiresAt] = now + 60_000
                    }
                }
                assertEquals("cancellation_limit", assertFailsWith<TeamFailure> { serviceB.cancel(admin, id()) }.code)
                a.query { RequestCancellations.update { it[expiresAt] = 0 } }
                serviceB.cancel(admin, id())
                assertEquals(1, count(), "Expired rows are reclaimed before enforcing the bound")
                a.query {
                    RequestCancellations.deleteAll()
                    val now = nowMillis()
                    RequestCancellations.batchInsert((1..RequestCancellationService.TEAM_LIMIT.toInt()).toList()) {
                        this[RequestCancellations.teamId] = admin.teamId; this[RequestCancellations.memberId] = created.member.id
                        this[RequestCancellations.requestId] = id(); this[RequestCancellations.expiresAt] = now + 60_000
                    }
                }
                assertEquals("cancellation_limit", assertFailsWith<TeamFailure> { serviceB.cancel(admin, id()) }.code)
            } }
        }
    }
}
