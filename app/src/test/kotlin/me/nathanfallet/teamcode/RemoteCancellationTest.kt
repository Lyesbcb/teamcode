package me.nathanfallet.teamcode

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.infrastructure.database.*
import me.nathanfallet.teamcode.infrastructure.secrets.CredentialCipher
import me.nathanfallet.teamcode.presentation.configureGateway
import org.testcontainers.mysql.MySQLContainer
import kotlin.test.*

class RemoteCancellationTest {
    @Test fun authenticatedCancellationOnAnotherHttpReplicaStopsWaitingAdmission() = runBlocking {
        MySQLContainer("mysql:8.4").withDatabaseName("teamcode").withTmpFs(mapOf("/var/lib/mysql" to "rw,nosuid,size=1g")).use { container ->
            container.start()
            Database(container.jdbcUrl, container.username, container.password).use { a ->
            Database(container.jdbcUrl, container.username, container.password).use { b ->
                a.migrate(); b.migrate()
                val teamA = SqlTeamService(a)
                val teamB = SqlTeamService(b)
                val password = teamA.bootstrap()!!
                val initial = teamA.login("admin", password, "HTTP cancellation")
                teamA.changePassword(initial.principal, password, "HTTP cancellation replacement")
                val session = teamA.login("admin", "HTTP cancellation replacement", "HTTP cancellation")
                val actor = session.principal
                val accounts = SqlAccountStore(a, CredentialCipher(ByteArray(32) { 47 }))
                val account = accounts.upsert(VerifiedProviderAccount(AccountIdentity("default", "anthropic", "http-cancellation", "org"), "HTTP cancellation"), ProviderCredentials("synthetic", "synthetic-refresh", Long.MAX_VALUE), null, "http-enrollment") as EnrollmentResult.Created
                accounts.handover("default", account.account.id, accessOnly = true)
                val admission = SqlAdmissionStore(a)
                admission.configure(actor, MemberPolicy(actor.member.id, maxConcurrent = 1))
                val held = admission.reserve(actor, 100, "claude-test")
                val cancellationA = RequestCancellationService(SqlRequestCancellationStore(a))
                val cancellationB = RequestCancellationService(SqlRequestCancellationStore(b))
                val finished = CompletableDeferred<Unit>()
                val streamingMode = AtomicBoolean(false)
                val streamingEntered = CompletableDeferred<Unit>()
                val streamingReleased = CompletableDeferred<Unit>()
                val gateway = CancellableGateway(object : GatewayService {
                    override suspend fun status() = GatewaySnapshot("test", 0, 0)
                    override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
                        assertFalse("x-teamcode-client-request-id" in request.headers, "Control metadata must not enter provider headers")
                        if (streamingMode.get()) {
                            receive(GenerationHead(200, "text/event-stream"), flow {
                                try {
                                    emit("data: started\n\n".toByteArray())
                                    streamingEntered.complete(Unit)
                                    awaitCancellation()
                                } finally { streamingReleased.complete(Unit) }
                            })
                            return
                        }
                        try {
                            AwaitAdmissionUseCase(admission).reserve(request.principal!!, AdmissionInput(100, "claude-test", "anthropic"))
                            fail("Cancelled work must not dispatch")
                        } finally { finished.complete(Unit) }
                    }
                }, cancellationA)
                val serverA = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configureGateway(gateway, team = teamA, cancellation = cancellationA) }
                val serverB = embeddedServer(Netty, host = "127.0.0.1", port = 0) { configureGateway(gateway, team = teamB, cancellation = cancellationB) }
                serverA.start(wait = false); serverB.start(wait = false)
                try {
                    val portA = serverA.engine.resolvedConnectors().single().port
                    val portB = serverB.engine.resolvedConnectors().single().port
                    val id = UUID.randomUUID().toString()
                    HttpClient(CIO).use { client ->
                        for (values in listOf(listOf("invalid"), listOf(id, id))) {
                            assertEquals(HttpStatusCode.BadRequest, client.post("http://127.0.0.1:$portA/v1/messages") {
                                bearerAuth(session.token); contentType(ContentType.Application.Json)
                                values.forEach { header("x-teamcode-client-request-id", it) }
                                setBody("""{"model":"claude-test","stream":true,"messages":[]}""")
                            }.status)
                        }
                        assertEquals(0, admission.statistics().queued)
                    }
                    Socket("127.0.0.1", portA).use { inference ->
                        // Keep this socket open throughout: TCP disconnect propagation cannot
                        // explain the cancellation delivered through the other replica.
                        val body = """{"model":"claude-test","stream":true,"messages":[]}""".toByteArray()
                        inference.getOutputStream().apply {
                            write("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$portA\r\nAuthorization: Bearer ${session.token}\r\nx-teamcode-client-request-id: $id\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                        withTimeout(5_000) { while (admission.statistics().queued == 0) delay(10) }
                        HttpClient(CIO).use { client ->
                            val endpoint = "http://127.0.0.1:$portB/api/v1/requests/$id/cancel"
                            assertEquals(HttpStatusCode.Unauthorized, client.post(endpoint).status)
                            assertEquals(HttpStatusCode.Unauthorized, client.post(endpoint) { header(HttpHeaders.Cookie, "teamcode_session=${session.token}") }.status)
                            assertFalse(finished.isCompleted)
                            assertEquals(HttpStatusCode.BadRequest, client.post("http://127.0.0.1:$portB/api/v1/requests/invalid/cancel") { bearerAuth(session.token) }.status)
                            assertEquals(HttpStatusCode.NoContent, client.post(endpoint) { bearerAuth(session.token) }.status)
                            assertEquals(HttpStatusCode.NoContent, client.post(endpoint) { bearerAuth(session.token) }.status)
                        }
                        withTimeout(3_000) { finished.await() }
                        assertEquals(0, admission.statistics().queued)
                        assertEquals(100, admission.capacity(actor).chargedAndReservedUnits)
                    }
                    streamingMode.set(true)
                    val streamId = UUID.randomUUID().toString()
                    Socket("127.0.0.1", portA).use { inference ->
                        val body = """{"model":"claude-test","stream":true,"messages":[]}""".toByteArray()
                        inference.getOutputStream().apply {
                            write("POST /v1/messages HTTP/1.1\r\nHost: 127.0.0.1:$portA\r\nAuthorization: Bearer ${session.token}\r\nx-teamcode-client-request-id: $streamId\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray())
                            write(body); flush()
                        }
                        withTimeout(3_000) { streamingEntered.await() }
                        HttpClient(CIO).use { client ->
                            assertEquals(HttpStatusCode.NoContent, client.post("http://127.0.0.1:$portB/api/v1/requests/$streamId/cancel") { bearerAuth(session.token) }.status)
                        }
                        withTimeout(3_000) { streamingReleased.await() }
                    }
                } finally { serverA.stop(0, 5_000); serverB.stop(0, 5_000); admission.settle(held, 0) }
            } }
        }
    }
}
