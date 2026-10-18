package me.nathanfallet.teamcode.infrastructure

import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.infrastructure.fixtures.FixtureGatewayService
import kotlin.test.*

class FixtureTest {
    @Test fun `fixture emits valid SSE JSON and cancellation releases active load`() = runTest {
        FixtureGatewayService.EVENTS.forEach { event -> Json.parseToJsonElement(event.substringAfter("data: ").trim()) }
        val gateway = FixtureGatewayService(10_000)
        val job = launch { gateway.forward(GenerationRequest(byteArrayOf(), emptyMap())) { _, chunks -> chunks.collect {} } }
        runCurrent()
        assertEquals(1, gateway.status().active)
        job.cancelAndJoin()
        assertEquals(0, gateway.status().active)
        assertEquals(0L, gateway.status().completed)
    }
}
