package me.nathanfallet.teamcode.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import me.nathanfallet.teamcode.domain.gateway.*
import me.nathanfallet.teamcode.domain.team.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class RequestCancellationTest {
    private val actor = Principal(Member("member", "member", Role.MEMBER, false, false), "token", "team")
    private val id = "00000000-0000-0000-0000-000000000001"
    private class Store : RequestCancellationStore {
        var cancelled = false
        var reads = 0
        override suspend fun cancel(actor: Principal, requestId: String) { cancelled = true }
        override suspend fun isCancelled(actor: Principal, requestId: String): Boolean { reads++; return cancelled }
        override suspend fun maintain() = Unit
    }

    @Test fun cancelBeforeDispatchAndInvalidIdsCannotStartWork() = runTest {
        val store = Store()
        val service = RequestCancellationService(store)
        assertEquals("invalid_request_id", assertFailsWith<TeamFailure> { service.cancel(actor, "bad/id") }.code)
        assertFalse(store.cancelled)
        service.cancel(actor, id)
        assertFailsWith<CancellationException> { service.run(actor, id) { fail("Already cancelled") } }
        assertEquals("password_change_required", assertFailsWith<TeamFailure> {
            service.cancel(actor.copy(member = actor.member.copy(passwordChangeRequired = true)), id)
        }.code)
    }

    @Test fun cancellationStopsWorkAndCleanupWithoutAffectingPeers() = runTest {
        val first = Store()
        val second = Store()
        var released = false
        val cancelled = async { RequestCancellationService(first).run(actor, id) {
            try { awaitCancellation() } finally { released = true }
        } }
        val survivor = async { RequestCancellationService(second).run(actor, id) { delay(2_000); "done" } }
        runCurrent()
        first.cancel(actor, id)
        advanceTimeBy(RequestCancellationService.POLL_MILLIS); runCurrent()
        assertFailsWith<CancellationException> { cancelled.await() }
        assertTrue(released)
        assertTrue(survivor.isActive)
        assertEquals("done", survivor.await())
        val reads = second.reads
        advanceTimeBy(5_000)
        assertEquals(reads, second.reads, "No monitor may outlive its request")
    }

    @Test fun databaseFailureStopsGenerationAndUntrackedClientsRemainCompatible() = runTest {
        val store = object : RequestCancellationStore {
            var reads = 0
            override suspend fun cancel(actor: Principal, requestId: String) = Unit
            override suspend fun isCancelled(actor: Principal, requestId: String): Boolean {
                if (++reads > 1) error("Synthetic unavailable database")
                return false
            }
            override suspend fun maintain() = Unit
        }
        var released = false
        assertFailsWith<IllegalStateException> {
            RequestCancellationService(store).run(actor, id) { try { awaitCancellation() } finally { released = true } }
        }
        assertTrue(released)
        var calls = 0
        val gateway = CancellableGateway(object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) { calls++ }
        }, RequestCancellationService(store))
        gateway.forward(GenerationRequest(byteArrayOf(), emptyMap())) { _, _ -> }
        assertEquals(1, calls)
    }

    @Test fun authenticatedLegacyRequestsAlsoRemainMonitoredWithoutAClientControlId() = runTest {
        val store = Store()
        var entered = false
        var released = false
        val gateway = CancellableGateway(object : GatewayService {
            override suspend fun status() = GatewaySnapshot("test", 0, 0)
            override suspend fun forward(request: GenerationRequest, receive: suspend (GenerationHead, kotlinx.coroutines.flow.Flow<ByteArray>) -> Unit) {
                assertNull(request.clientRequestId)
                entered = true
                try { awaitCancellation() } finally { released = true }
            }
        }, RequestCancellationService(store))
        val pending = async { gateway.forward(GenerationRequest(byteArrayOf(), emptyMap(), actor)) { _, _ -> } }
        runCurrent(); assertTrue(entered)
        store.cancelled = true
        advanceTimeBy(RequestCancellationService.POLL_MILLIS); runCurrent()
        assertFailsWith<CancellationException> { pending.await() }
        assertTrue(released)
    }
}
