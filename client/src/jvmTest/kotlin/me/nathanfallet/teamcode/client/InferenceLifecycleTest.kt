package me.nathanfallet.teamcode.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.test.*

class InferenceLifecycleTest {
    @Test fun cancellationIsPerRequestAndClosingTheSdkCancelsRemainingInference() = runBlocking {
        withTimeout(5_000) {
            val entered = Channel<String>(Channel.UNLIMITED)
            val finished = Channel<String>(Channel.UNLIMITED)
            val sdk = TeamCodeClient("https://team.example", { "synthetic-token" }) { configure ->
                HttpClient(MockEngine { request ->
                    entered.send(request.url.encodedPath)
                    try { awaitCancellation() }
                    finally { finished.trySend(request.url.encodedPath) }
                }) { configure(this) }
            }
            try {
                val first = launch { sdk.forwardInference("/v1/messages", "{}".toByteArray()) { fail("No headers were sent") } }
                val second = launch { sdk.forwardInference("/v1/responses", "{}".toByteArray()) { fail("No headers were sent") } }
                assertEquals(setOf("/v1/messages", "/v1/responses"), setOf(entered.receive(), entered.receive()))
                first.cancelAndJoin()
                assertEquals("/v1/messages", finished.receive())
                assertTrue(second.isActive, "Cancelling one inference must not cancel another")
                sdk.close()
                second.join()
                assertEquals("/v1/responses", finished.receive())
                assertFailsWith<IllegalStateException> { sdk.forwardMessages("{}".toByteArray()) {} }
            } finally { sdk.close() }
        }
    }
}
