package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.capacity.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import me.nathanfallet.teamcode.domain.policy.*
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import me.nathanfallet.teamcode.infrastructure.gateway.*
import me.nathanfallet.teamcode.infrastructure.providers.ClaudeTransport
import me.nathanfallet.teamcode.infrastructure.providers.CodexTransport
import kotlin.test.*

class PoolGatewayTest {
    private val actor = Principal(Member("member", "alice", Role.MEMBER, false, false), "token", "team")
    private val account = AccountRecord("account", AccountIdentity("team", "anthropic", "provider-id", "org-id"), "Max", 1, false, null, 0, AccountState.READY)
    private fun request() = GenerationRequest("""{"model":"claude-opus-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"Hello"}]}""".toByteArray(), emptyMap(), actor)
    @Test fun streamsExactlyOnceAndSettlesAllUsageCategories() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        val events = FixtureGatewayService.EVENTS.joinToString("").replace("\"input_tokens\":1", "\"input_tokens\":10,\"cache_read_input_tokens\":20,\"cache_creation_input_tokens\":30")
        val http = HttpClient(MockEngine { req ->
            calls++
            assertEquals("Bearer provider-secret", req.headers[HttpHeaders.Authorization])
            assertTrue(req.headers["anthropic-beta"]!!.contains("oauth-2025-04-20"))
            respond(events, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val output = StringBuilder()
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect { output.append(it.decodeToString()) } }
            assertEquals(events, output.toString())
            assertEquals(1, calls)
            assertEquals(65, store.settled)
            assertEquals(0, gateway.status().active)
        }
    }
    @Test fun committedDisconnectIsNeverReplayedAndKeepsUnknownReservation() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        val http = HttpClient(MockEngine { calls++; respond(FixtureGatewayService.EVENTS.first(), headers = headersOf(HttpHeaders.ContentType, "text/event-stream")) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val cancelled = assertFailsWith<CancellationException> { gateway.forward(request()) { _, chunks -> chunks.collect { throw CancellationException("downstream disconnected") } } }
            assertEquals("downstream disconnected", cancelled.message)
            assertEquals(1, calls)
            assertTrue(store.didSettle)
            assertNull(store.settled)
            assertEquals(0, gateway.status().active)
        }
    }
    @Test fun providerRejectionDoesNotExposeCredentialsOrBurnAllowance() = runBlocking {
        val store = FakeAdmission(account)
        val http = HttpClient(MockEngine { respond("provider-secret", HttpStatusCode.TooManyRequests, headersOf("retry-after", "10")) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            gateway.forward(request()) { head, chunks ->
                assertEquals(429, head.status)
                assertEquals("10", head.headers["retry-after"])
                chunks.collect { assertFalse(it.decodeToString().contains("provider-secret")) }
            }
            assertEquals(0, store.settled)
            assertTrue(store.cooled)
        }
    }
    @Test fun codexStreamWithoutContentTypeUsesResponsesContract() = runBlocking {
        val codex = account.copy(identity = account.identity.copy(provider = "codex"))
        val store = FakeAdmission(codex)
        val events = """data: {"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":2}}}""" + "\n\n"
        val http = HttpClient(MockEngine { respond(events, headers = Headers.Empty) }) { install(HttpTimeout) }
        http.use {
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(CodexTransport(it)), FakeModelPolicies())
            val request = GenerationRequest("""{"model":"gpt-6-luna","stream":true,"instructions":"brief","input":[]}""".toByteArray(), emptyMap(), actor, WireProtocol.RESPONSES)
            val output = StringBuilder()
            gateway.forward(request) { head, chunks ->
                assertEquals("text/event-stream", head.contentType)
                chunks.collect { output.append(it.decodeToString()) }
            }
            assertEquals(events, output.toString())
            assertEquals(20, store.settled)
            assertEquals("codex", store.selectedProvider)
        }
    }
    @Test fun usageMeterHandlesChunkBoundariesAndMissingFinalUsage() {
        val bytes = FixtureGatewayService.EVENTS.joinToString("").toByteArray()
        val meter = SseUsageMeter()
        bytes.forEach { meter.accept(byteArrayOf(it)) }
        assertEquals(6, meter.settledUnits)
        val missing = SseUsageMeter()
        missing.accept(FixtureGatewayService.EVENTS.first().toByteArray())
        assertNull(missing.settledUnits)
    }
}
private class FakeAdmission(private val account: AccountRecord) : AdmissionStore {
    var settled: Long? = null
    var didSettle = false
    var cooled = false
    var selectedProvider: String? = null
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String): Reservation { selectedProvider = provider; return Reservation("request", account, units) }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?) { didSettle = true; settled = actualUnits }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal) = emptyList<PoolBudget>()
    override suspend fun configurePool(actor: Principal, budget: PoolBudget) = budget
    override suspend fun quotas(actor: Principal) = emptyList<AccountQuota>()
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = Unit
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) { cooled = true }
}

private class FakeModelPolicies : ModelPolicyStore {
    override suspend fun get(teamId: String) = ModelPolicy()
    override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
}
