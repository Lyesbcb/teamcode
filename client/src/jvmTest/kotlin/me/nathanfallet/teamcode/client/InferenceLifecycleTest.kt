package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class InferenceLifecycleTest {
    @Test fun cancellationIsPerRequestAndClosingTheSdkCancelsRemainingInference() = runBlocking {
        withTimeout(5_000) {
            val entered = Channel<String>(Channel.UNLIMITED)
            val finished = Channel<String>(Channel.UNLIMITED)
            val cancellations = Channel<String>(Channel.UNLIMITED)
            val ids = mutableMapOf<String, String>()
            var credential = "synthetic-token"
            val sdk = TeamCodeClient("https://team.example", { credential }) { configure ->
                HttpClient(MockEngine { request ->
                    if (request.url.encodedPath.startsWith("/api/v1/requests/")) {
                        assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
                        cancellations.send(request.url.encodedPath)
                        return@MockEngine respond("", HttpStatusCode.NoContent)
                    }
                    ids[request.url.encodedPath] = request.headers["x-teamcode-client-request-id"]!!
                    entered.send(request.url.encodedPath)
                    try { awaitCancellation() }
                    finally { finished.trySend(request.url.encodedPath) }
                }) { configure(this) }
            }
            try {
                val first = launch { sdk.forwardInference("/v1/messages", "{}".toByteArray()) { fail("No headers were sent") } }
                val second = launch { sdk.forwardInference("/v1/responses", "{}".toByteArray()) { fail("No headers were sent") } }
                assertEquals(setOf("/v1/messages", "/v1/responses"), setOf(entered.receive(), entered.receive()))
                assertEquals(2, ids.values.toSet().size)
                credential = "replacement-token"
                first.cancelAndJoin()
                assertEquals("/api/v1/requests/${ids["/v1/messages"]}/cancel", cancellations.receive())
                assertEquals("/v1/messages", finished.receive())
                assertTrue(second.isActive, "Cancelling one inference must not cancel another")
                sdk.close()
                second.join()
                assertEquals("/api/v1/requests/${ids["/v1/responses"]}/cancel", cancellations.receive())
                assertEquals("/v1/responses", finished.receive())
                assertFailsWith<IllegalStateException> { sdk.forwardMessages("{}".toByteArray()) {} }
            } finally { sdk.close() }
        }
    }

    @Test fun successfulInferenceDoesNotSendCancellationAndControlFailuresAreBounded() = runBlocking {
        var inferenceRequests = 0
        var controlRequests = 0
        val entered = CompletableDeferred<Unit>()
        val sdk = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
            HttpClient(MockEngine { request ->
                if (request.url.encodedPath.startsWith("/api/")) {
                    controlRequests++
                    respond("", HttpStatusCode.ServiceUnavailable)
                } else {
                    inferenceRequests++
                    if (inferenceRequests > 1) { entered.complete(Unit); awaitCancellation() }
                    respond("done", HttpStatusCode.OK)
                }
            }) { configure(this) }
        }
        try {
            sdk.forwardMessages("{}".toByteArray()) { assertEquals("done", it.bodyAsText()) }
            assertEquals(0, controlRequests)
            val pending = launch { sdk.forwardMessages("{}".toByteArray()) {} }
            entered.await()
            withTimeout(4_000) { pending.cancelAndJoin() }
            assertEquals(2, controlRequests, "Only idempotent control calls may retry")
            assertEquals(2, inferenceRequests, "An ambiguous inference must never replay")
        } finally { sdk.close() }
    }
}
