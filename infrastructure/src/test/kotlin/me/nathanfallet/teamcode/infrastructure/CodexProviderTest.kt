package me.nathanfallet.teamcode.infrastructure

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlinx.serialization.json.*
import me.nathanfallet.teamcode.domain.accounts.*
import me.nathanfallet.teamcode.domain.team.TeamFailure
import me.nathanfallet.teamcode.infrastructure.gateway.SseUsageMeter
import me.nathanfallet.teamcode.infrastructure.providers.*
import kotlin.test.*

class CodexProviderTest {
    private fun credentials(account: String = "workspace-1") = ProviderCredentials(
        "header." + Base64.getUrlEncoder().withoutPadding().encodeToString("""{"https://api.openai.com/auth":{"chatgpt_account_id":"$account"}}""".toByteArray()) + ".signature",
        "refresh-secret", Long.MAX_VALUE)
    @Test fun remoteIdentityMustMatchAccountHint() = runBlocking {
        val http = HttpClient(MockEngine { request ->
            assertEquals("chatgpt.com", request.url.host)
            assertEquals("workspace-1", request.headers["ChatGPT-Account-Id"])
            respond("""{"account_id":"workspace-1","user_id":"user-1","plan_type":"pro"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }) { install(HttpTimeout) }
        http.use {
            val account = CodexAccountVerifier(it).verify("team", credentials())
            assertEquals(AccountIdentity("team", "codex", "user-1", "workspace-1"), account.identity)
        }
        val mismatch = HttpClient(MockEngine { respond("""{"account_id":"different","user_id":"user-1"}""") }) { install(HttpTimeout) }
        mismatch.use { assertFailsWith<TeamFailure> { CodexAccountVerifier(it).verify("team", credentials()) } }
    }
    @Test fun subscriptionRequestKeepsToolsAndUsesOnlyItsProviderCredentials() = runBlocking {
        val body = Json.parseToJsonElement("""{"model":"gpt-6-luna","stream":true,"instructions":"brief","input":[],"tools":[{"type":"function","name":"lookup","parameters":{"type":"object"}}]}""").jsonObject
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://chatgpt.com/backend-api/codex/responses", request.url.toString())
            assertEquals("workspace-1", request.headers["ChatGPT-Account-Id"])
            assertEquals("Bearer ${credentials().accessToken}", request.headers[HttpHeaders.Authorization])
            val sent = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(body["tools"], sent["tools"])
            assertEquals(JsonPrimitive(false), sent["store"])
            assertNull(request.headers["x-api-key"])
            respond("data: [DONE]\n\n", headers = headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }) { install(HttpTimeout) }
        http.use {
            val account = AccountRecord("id", AccountIdentity("team", "codex", "user-1", "workspace-1"), "Codex", 1, false, null, 0, AccountState.ACCESS_ONLY)
            CodexTransport(it).send(account, credentials(), body, emptyMap()) { response -> assertEquals(200, response.status.value) }
            assertFailsWith<TeamFailure> { CodexTransport(it).send(account, credentials(), JsonObject(body + ("store" to JsonPrimitive(true))), emptyMap()) {} }
        }
    }
    @Test fun responseUsageDoesNotDoubleCountCacheOrReasoning() {
        val meter = SseUsageMeter()
        val terminal = """data: {"type":"response.completed","response":{"usage":{"input_tokens":100,"input_tokens_details":{"cached_tokens":80},"output_tokens":10,"output_tokens_details":{"reasoning_tokens":8}}}}""" + "\n\ndata: [DONE]\n\n"
        terminal.toByteArray().forEach { meter.accept(byteArrayOf(it)) }
        assertEquals(150, meter.settledUnits)
        meter.accept(terminal.toByteArray())
        assertEquals(150, meter.settledUnits)
        val missing = SseUsageMeter()
        missing.accept("data: {\"type\":\"response.completed\",\"response\":{}}\n\n".toByteArray())
        assertNull(missing.settledUnits)
    }
    @Test fun subscriptionWindowsKeepDurationsAndModelScopesDistinct() {
        val value = Json.parseToJsonElement("""{"rate_limit":{"primary_window":{"used_percent":20,"limit_window_seconds":18000,"reset_at":2000},"secondary_window":{"used_percent":40,"limit_window_seconds":604800,"reset_at":3000}},"additional_rate_limits":[{"limit_name":"gpt-6-luna","rate_limit":{"primary_window":{"used_percent":99,"limit_window_seconds":18000,"reset_at":2000}}}]}""").jsonObject
        val quotas = CodexTransport.parseUsage("account", value, 1000)
        assertEquals(listOf("five_hour", "seven_day", "model:gpt-6-luna:five_hour"), quotas.map { it.window })
        assertEquals(listOf(.2, .4, .99), quotas.map { it.utilization })
    }
}
