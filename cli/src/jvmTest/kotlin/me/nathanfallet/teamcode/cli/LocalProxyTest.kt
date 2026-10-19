package me.nathanfallet.teamcode.cli

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import me.nathanfallet.teamcode.cli.proxy.localProxy
import me.nathanfallet.teamcode.client.TeamCodeClient
import kotlin.test.*

class LocalProxyTest {
    private val localToken = "synthetic-local-token-long-enough-for-tests"
    private fun payload() = """{"model":"fixture","stream":true,"messages":[]}"""

    @Test fun `local gateway credential replaces caller auth and SSE bytes are preserved`() = testApplication {
        var calls = 0
        val engine = MockEngine { request ->
            calls++
            assertEquals("Bearer server-token", request.headers[HttpHeaders.Authorization])
            assertNull(request.headers["x-api-key"])
            assertEquals("codex-session", request.headers["session-id"])
            assertEquals("/v1/messages", request.url.encodedPath)
            respond("event: done\ndata: {}\n\n", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
        }
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(engine) { config(this) } }
        application { localProxy(sdk, localToken) }
        try {
            val response = client.post("/v1/messages") {
                contentType(ContentType.Application.Json)
                header("x-api-key", localToken)
                header("session-id", "codex-session")
                setBody(payload())
            }
            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("event: done\ndata: {}\n\n", response.bodyAsText())
            assertEquals(1, calls)
        } finally { sdk.close() }
    }

    @Test fun `unkeyed browser and oversized requests never reach upstream`() = testApplication {
        var calls = 0
        val sdk = TeamCodeClient("https://self.hosted.example", { "server-token" }) { config -> HttpClient(MockEngine {
            calls++; respond("unexpected")
        }) { config(this) } }
        application { localProxy(sdk, localToken, maximumBodyBytes = 64) }
        try {
            assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages") {
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.Forbidden, client.post("/v1/messages") {
                header("x-api-key", localToken); header(HttpHeaders.Origin, "https://attacker.example")
                contentType(ContentType.Application.Json); setBody(payload())
            }.status)
            assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/messages") {
                header("x-api-key", localToken); contentType(ContentType.Application.Json); setBody("x".repeat(65))
            }.status)
            assertEquals(0, calls)
        } finally { sdk.close() }
    }
}
