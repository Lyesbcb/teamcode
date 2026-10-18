package me.nathanfallet.teamcode.presentation

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.flowOf
import me.nathanfallet.teamcode.api.models.*
import me.nathanfallet.teamcode.client.TeamCodeClient
import me.nathanfallet.teamcode.domain.gateway.*
import kotlin.test.*

class GatewayTest {
    private val accessToken = "synthetic-teamcode-token-for-tests-only"
    private class StubGateway : GatewayService {
        var requests = 0
        override fun status() = GatewaySnapshot("fixture", 0, requests.toLong())
        override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
            requests++
            assertFalse(request.headers.containsKey("authorization"))
            receive(GenerationHead(200, "text/event-stream"), flowOf("data: first\n\n".encodeToByteArray(), "data: last\n\n".encodeToByteArray()))
        }
    }
    @Test fun `real SDK authenticates and reads typed status and capabilities`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        val sdk = TeamCodeClient("http://localhost", { accessToken }) { config -> createClient { config(this) } }
        try {
            assertEquals(1, sdk.capabilities().apiVersion)
            assertEquals(GatewayStatus("fixture", 0, 0), sdk.status())
        } finally { sdk.close() }
    }
    @Test fun `unkeyed loopback is refused before any provider work`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        assertEquals(HttpStatusCode.OK, client.get("/health").status)
        assertEquals(HttpStatusCode.Unauthorized, client.get("/api/v1/status").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/messages").status)
        assertEquals(0, gateway.requests)
    }
    @Test fun `stream traverses SDK unchanged and private headers do not reach gateway`() = testApplication {
        application { configureGateway(StubGateway(), accessToken) }
        val sdk = TeamCodeClient("http://localhost", { accessToken }) { config -> createClient { config(this) } }
        try {
            sdk.forwardMessages("""{"model":"fixture","stream":true,"messages":[]}""".encodeToByteArray()) {
                assertEquals(HttpStatusCode.OK, it.status)
                assertEquals("data: first\n\ndata: last\n\n", it.bodyAsText())
            }
        } finally { sdk.close() }
    }
    @Test fun `oversized and malformed inputs are rejected before provider work`() = testApplication {
        val gateway = StubGateway()
        application { configureGateway(gateway, accessToken) }
        for ((body, expected) in listOf("{bad" to HttpStatusCode.BadRequest, "x".repeat(2 * 1024 * 1024 + 1) to HttpStatusCode.PayloadTooLarge)) {
            assertEquals(expected, client.post("/v1/messages") { bearerAuth(accessToken); contentType(ContentType.Application.Json); setBody(body) }.status)
        }
        assertEquals(0, gateway.requests)
    }
}
