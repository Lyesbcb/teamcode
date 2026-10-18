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
import me.nathanfallet.teamcode.domain.routing.*
import kotlinx.serialization.json.*
import io.ktor.http.content.TextContent

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
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
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
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
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
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(it)), FakeModelPolicies())
            val error = assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail("Rejected attempts must not commit output") } }
            assertEquals(429, error.status)
            assertFalse(error.message.orEmpty().contains("provider-secret"))
            assertEquals(1, store.charges.size)
            assertTrue(store.charges.all { it == 0L })
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
            val source = object : CredentialSource { override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE) }
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
    private fun routes(vararg targets: RouteTarget) = object : RoutingStore {
        override suspend fun list(teamId: String) = listOf(RouteRule("test", "*", targets.toList()))
        override suspend fun save(actor: Principal, rule: RouteRule) = error("unused")
        override suspend fun delete(actor: Principal, id: String, version: Long) = error("unused")
    }
    private val credentials = object : CredentialSource {
        override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?) = ProviderCredentials("provider-secret", "refresh-secret", Long.MAX_VALUE)
    }
    @Test fun definiteRejectionFallsBackAndTranslatesWithoutLosingToolConversation() = runBlocking {
        val store = FakeAdmission(account)
        val calls = mutableListOf<String>()
        val events = listOf(
            """{"type":"response.created","response":{"id":"resp_1"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","call_id":"call_2","name":"calculate"}}""",
            """{"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\"x\":3}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","call_id":"call_2","name":"calculate","arguments":"{\"x\":3}"}}""",
            """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":2}}}""",
        ).joinToString("") { "data: $it\n\n" }
        val http = HttpClient(MockEngine { req ->
            calls += req.url.host
            if (req.url.host == "api.anthropic.com") respond("rate limited", HttpStatusCode.TooManyRequests)
            else {
                val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
                assertEquals("gpt-6-astra", body["model"]!!.jsonPrimitive.content)
                assertEquals("function_call_output", body["input"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
                assertEquals("call_1", body["input"]!!.jsonArray.last().jsonObject["call_id"]!!.jsonPrimitive.content)
                respond(events, headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }) { install(HttpTimeout) }
        http.use {
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(it), CodexTransport(it)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            val body = """{"model":"claude-opus-test","stream":true,"max_tokens":100,"messages":[{"role":"user","content":"calculate"},{"role":"assistant","content":[{"type":"tool_use","id":"call_1","name":"calculate","input":{"x":2}}]},{"role":"user","content":[{"type":"tool_result","tool_use_id":"call_1","content":"4"}]}],"tools":[{"name":"calculate","input_schema":{"type":"object"}}]}"""
            val output = StringBuilder()
            gateway.forward(request().copy(body = body.toByteArray())) { head, chunks ->
                assertEquals("codex", head.headers["x-teamcode-provider"])
                chunks.collect { output.append(it.decodeToString()) }
            }
            assertEquals(2, calls.size)
            assertTrue(output.contains("tool_use"))
            assertTrue(output.contains("call_2"))
            assertTrue(output.contains("message_stop"))
            assertEquals<List<Long?>>(listOf(0L, 20L), store.charges)
        }
    }
    @Test fun exhaustsPreferredPoolBeforeCrossProviderFallbackWithoutLeakingUpstream429() = runBlocking {
        val store = FakeAdmission(account, accountsPerProvider = 7)
        var calls = 0
        HttpClient(MockEngine { req ->
            calls++
            assertEquals("api.anthropic.com", req.url.host, "A preferred account remains; do not switch providers yet")
            if (calls < 7) respond("rate limited", HttpStatusCode.TooManyRequests)
            else respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            var responses = 0
            gateway.forward(request()) { head, chunks -> responses++; assertEquals(200, head.status); chunks.collect {} }
            assertEquals(1, responses)
            assertEquals(7, calls)
            assertEquals(List(6) { 0L }, store.charges.take(6))
            assertEquals(6L, store.charges.last())
        }
    }

    @Test fun modelScopedRejectionAllowsAnotherModelOnTheSameAccount() = runBlocking {
        val store = FakeAdmission(account)
        val seen = mutableListOf<String>()
        HttpClient(MockEngine { req ->
            val model = Json.parseToJsonElement((req.body as TextContent).text).jsonObject["model"]!!.jsonPrimitive.content
            seen += model
            if (model == "gpt-6-astra") respond("limited", HttpStatusCode.TooManyRequests, headersOf(
                "x-codex-gpt-6-astra-limit-name" to listOf("gpt-6-astra"),
                "x-codex-gpt-6-astra-secondary-used-percent" to listOf("100"),
                "x-codex-gpt-6-astra-secondary-window-minutes" to listOf("10080"),
                "x-codex-gpt-6-astra-secondary-reset-at" to listOf((System.currentTimeMillis() / 1000 + 86400).toString()),
            )) else respond("data: {\"type\":\"response.created\",\"response\":{\"id\":\"test\"}}\n\ndata: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("codex", "gpt-6-astra"), RouteTarget("codex", "gpt-6-sol")))
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect {} }
            assertEquals(listOf("gpt-6-astra", "gpt-6-sol"), seen)
            assertFalse(store.cooled, "A model-scoped rejection must not cool the entire account")
            assertEquals<List<Long?>>(listOf(0L, 6L), store.charges)
        }
    }
    @Test fun forbiddenResponseUsesTemporaryCooldownWithoutRevokingCredentials() = runBlocking {
        val store = FakeAdmission(account)
        HttpClient(MockEngine { respond("forbidden", HttpStatusCode.Forbidden) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail() } }
            assertTrue(store.cooled)
            assertFalse(store.reconnect)
        }
    }
    @Test fun rejectedUnexpiredTokenRefreshesOnceBeforeReplayingTheRejectedRequest() = runBlocking {
        val store = FakeAdmission(account)
        val tokens = mutableListOf<String>()
        val rejectedTokens = mutableListOf<String?>()
        val source = object : CredentialSource {
            override suspend fun fresh(account: AccountRecord, rejectedAccessToken: String?): ProviderCredentials {
                rejectedTokens += rejectedAccessToken
                return ProviderCredentials(if (rejectedAccessToken == null) "old-token" else "new-token", "refresh", Long.MAX_VALUE)
            }
        }
        HttpClient(MockEngine { req ->
            tokens += req.headers[HttpHeaders.Authorization]!!
            if (tokens.size == 1) respond("revoked", HttpStatusCode.Unauthorized)
            else respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, source, listOf(ClaudeTransport(http)), FakeModelPolicies())
            gateway.forward(request()) { head, chunks -> assertEquals(200, head.status); chunks.collect {} }
            assertEquals(listOf(null, "old-token"), rejectedTokens)
            assertEquals(listOf("Bearer old-token", "Bearer new-token"), tokens)
            assertFalse(store.cooled)
            assertEquals<List<Long?>>(listOf(6L), store.charges)
        }
    }
    @Test fun repeatedUnauthorizedIsBoundedAndAmbiguousRetryKeepsItsReservation() = runBlocking {
        for (second in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.InternalServerError)) {
            val store = FakeAdmission(account)
            var calls = 0
            HttpClient(MockEngine { calls++; respond("rejected", if (calls == 1) HttpStatusCode.Unauthorized else second) }) { install(HttpTimeout) }.use { http ->
                val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
                if (second == HttpStatusCode.Unauthorized) {
                    assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail() } }
                    assertEquals(0L, store.settled)
                    assertTrue(store.reconnect)
                } else {
                    gateway.forward(request()) { head, chunks -> assertEquals(502, head.status); chunks.collect {} }
                    assertNull(store.settled, "A rejected first attempt must not refund a later ambiguous attempt")
                }
                assertEquals(2, calls)
            }
        }
    }
    @Test fun boundedSearchIsNotMisreportedAsExhaustedCapacity() = runBlocking {
        val store = FakeAdmission(account, accountsPerProvider = 65)
        var calls = 0
        HttpClient(MockEngine { calls++; respond("rate limited", HttpStatusCode.TooManyRequests) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            val error = assertFailsWith<TeamFailure> { gateway.forward(request()) { _, _ -> fail("No provider response should be committed") } }
            assertEquals(503, error.status)
            assertEquals("routing_attempt_limit", error.code)
            assertEquals(64, calls)
            assertEquals(64, store.charges.size)
            assertTrue(store.charges.all { it == 0L })
        }
    }
    @Test fun ambiguousServerErrorDoesNotTryFallback() = runBlocking {
        val store = FakeAdmission(account)
        var calls = 0
        HttpClient(MockEngine { calls++; respond("secret diagnostic", HttpStatusCode.InternalServerError) }) { install(HttpTimeout) }.use { http ->
            val gateway = PoolGateway(store, credentials, listOf(ClaudeTransport(http), CodexTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic"), RouteTarget("codex", "gpt-6-astra")))
            gateway.forward(request()) { head, chunks -> assertEquals(502, head.status); chunks.collect { assertFalse(it.decodeToString().contains("secret diagnostic")) } }
            assertEquals(1, calls)
            assertEquals<List<Long?>>(listOf(null), store.charges)
        }
    }
    @Test fun forbiddenResolvedModelCannotBypassPolicyAndNativeFieldsStayOpaque() = runBlocking {
        var calls = 0
        HttpClient(MockEngine { req ->
            calls++
            val body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            assertEquals("preserved", body["future_field"]!!.jsonPrimitive.content)
            respond(FixtureGatewayService.EVENTS.joinToString(""), headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }.use { http ->
            val denied = PoolGateway(FakeAdmission(account), credentials, listOf(ClaudeTransport(http)), FakeModelPolicies(), routes(RouteTarget("anthropic", "claude-fable-test")))
            assertEquals("model_not_allowed", assertFailsWith<TeamFailure> { denied.forward(request()) { _, _ -> fail() } }.code)
            assertEquals(0, calls)
            val native = PoolGateway(FakeAdmission(account), credentials, listOf(ClaudeTransport(http)), FakeModelPolicies())
            val body = Json.parseToJsonElement(request().body.decodeToString()).jsonObject
            native.forward(request().copy(body = JsonObject(body + ("future_field" to JsonPrimitive("preserved"))).toString().toByteArray())) { _, chunks -> chunks.collect {} }
            assertEquals(1, calls)
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
private class FakeAdmission(private val account: AccountRecord, private val accountsPerProvider: Int = 1) : AdmissionStore {
    override suspend fun statistics() = AdmissionStatistics(0, 0)
    val charges = mutableListOf<Long?>()
    var settled: Long? = null
    var didSettle = false
    var cooled = false
    var reconnect = false
    var selectedProvider: String? = null
    override suspend fun reserve(actor: Principal, units: Long, model: String, provider: String, excludedAccountIds: Set<String>): Reservation {
        val id = (0 until accountsPerProvider).map { "$provider-$it" }.firstOrNull { it !in excludedAccountIds }
            ?: throw TeamFailure("pool_unavailable", 429, "No eligible account.")
        selectedProvider = provider
        return Reservation("request-$id", account.copy(id = id, identity = account.identity.copy(provider = provider)), units)
    }
    override suspend fun settle(reservation: Reservation, actualUnits: Long?, completed: Boolean) { didSettle = true; settled = actualUnits; charges += actualUnits }
    override suspend fun capacity(actor: Principal, memberId: String): MemberCapacity = error("unused")
    override suspend fun configure(actor: Principal, policy: MemberPolicy): MemberPolicy = error("unused")
    override suspend fun poolBudgets(actor: Principal) = emptyList<PoolBudget>()
    override suspend fun configurePool(actor: Principal, budget: PoolBudget) = budget
    override suspend fun quotas(actor: Principal) = emptyList<AccountQuota>()
    override suspend fun observe(accountId: String, observations: List<AccountQuota>) = Unit
    override suspend fun coolDown(accountId: String, untilMillis: Long, reconnect: Boolean) { cooled = true; this.reconnect = reconnect }
}

private class FakeModelPolicies : ModelPolicyStore {
    override suspend fun get(teamId: String) = ModelPolicy()
    override suspend fun save(actor: Principal, policy: ModelPolicy) = policy
}
